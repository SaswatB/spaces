package com.spaces.daemon

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

data class NfsOp(
        val mountId: String,
        val relativePath: String,
        val kind: NfsOpKind,
        val targetRelativePath: String? = null
)

enum class NfsOpKind {
    Create,
    Write,
    Mkdir,
    Setattr,
    Rename,
    Remove,
    Rmdir
}

class ReplicationEngine {
    private val suppressed = ConcurrentHashMap<String, Instant>()
    private val ttl = Duration.ofSeconds(5)

    fun suppress(key: String) {
        cleanup()
        suppressed[key] = Instant.now()
    }

    fun isSuppressed(key: String): Boolean {
        cleanup()
        return suppressed.containsKey(key)
    }

    private fun cleanup() {
        val now = Instant.now()
        suppressed.entries.removeIf { now.isAfter(it.value.plus(ttl)) }
    }
}

class ReplicationService(
        private val db: SpacesDatabase,
        private val engine: ReplicationEngine,
        private val vfs: SpacesVfs
) {
    private val logger = LoggerFactory.getLogger(ReplicationService::class.java)
    private val serialReplayDispatch = Executors.newSingleThreadExecutor()
    private val serialInvalidationDispatch = Executors.newSingleThreadExecutor()
    private val idleWaitTimeoutMs =
            (System.getenv("SPACES_REPLICATION_IDLE_WAIT_TIMEOUT_MS") ?: "10000").toLong()
    private val pendingReplayTasks = AtomicInteger(0)
    private val pendingInvalidationTasks = AtomicInteger(0)
    private val pendingInvalidationByMount = ConcurrentHashMap<String, AtomicInteger>()

    fun handleOp(op: NfsOp) {
        if (isReplicationIgnoredOp(op)) {
            return
        }
        val layerId = resolveLayerId(op.mountId) ?: return
        val suppressionKey = suppressionKey(op)
        if (engine.isSuppressed(suppressionKey)) {
            return
        }
        val targetMountIds = mountTargetsForLayer(layerId)
        for (targetMountId in targetMountIds) {
            if (targetMountId == op.mountId) continue
            val targetKey = suppressionKey(op.copy(mountId = targetMountId))
            engine.suppress(targetKey)
            val queuedAt = System.nanoTime()
            pendingReplayTasks.incrementAndGet()
            // Dispatch replay off the serving NFS thread.
            // Replay can touch mount paths to generate watcher-visible fs events, and we do not
            // want that I/O to synchronously re-enter NFS handling for the current request.
            serialReplayDispatch.execute {
                try {
                    PerfStats.observe("replication.replay.queue_delay", System.nanoTime() - queuedAt)
                    val runStart = System.nanoTime()
                    runCatching { vfs.replay(op.mountId, targetMountId, op) }
                            .onFailure { error ->
                                logger.warn(
                                        "Replay failed sourceMountId={} targetMountId={} opKind={} path={} targetPath={}",
                                        op.mountId,
                                        targetMountId,
                                        op.kind,
                                        op.relativePath,
                                        op.targetRelativePath,
                                        error
                                )
                            }
                    PerfStats.observe("replication.replay.task", System.nanoTime() - runStart)
                } finally {
                    pendingReplayTasks.decrementAndGet()
                }
            }
        }
    }

    fun handleLayerSwitchInvalidation(mountPath: String, oldLayerId: String?, newLayerId: String?) {
        val queuedAt = System.nanoTime()
        pendingInvalidationTasks.incrementAndGet()
        pendingInvalidationForMount(mountPath).incrementAndGet()
        serialInvalidationDispatch.execute {
            try {
                PerfStats.observe("replication.invalidation.queue_delay", System.nanoTime() - queuedAt)
                val runStart = System.nanoTime()
                runCatching { vfs.invalidateMountForLayerSwitch(mountPath, oldLayerId, newLayerId) }
                        .onFailure { error ->
                            logger.warn(
                                    "Layer-switch invalidation failed mountPath={} oldLayerId={} newLayerId={}",
                                    mountPath,
                                    oldLayerId,
                                    newLayerId,
                                    error
                            )
                        }
                PerfStats.observe("replication.invalidation.task", System.nanoTime() - runStart)
            } finally {
                pendingInvalidationTasks.decrementAndGet()
                pendingInvalidationForMount(mountPath).decrementAndGet()
            }
        }
    }

    fun idleSnapshot(): ReplicationIdleSnapshot {
        val replay = pendingReplayTasks.get()
        val invalidation = pendingInvalidationTasks.get()
        return ReplicationIdleSnapshot(
                replayPending = replay,
                invalidationPending = invalidation,
                idle = replay == 0 && invalidation == 0
        )
    }

    fun awaitIdle(timeoutMs: Long = idleWaitTimeoutMs): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (idleSnapshot().idle) {
                return true
            }
            Thread.sleep(10)
        }
        return idleSnapshot().idle
    }

    fun awaitMountInvalidation(mountPath: String, timeoutMs: Long = idleWaitTimeoutMs): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if ((pendingInvalidationByMount[mountPath]?.get() ?: 0) == 0) {
                return true
            }
            Thread.sleep(10)
        }
        return (pendingInvalidationByMount[mountPath]?.get() ?: 0) == 0
    }

    private fun resolveLayerId(mountId: String): String? {
        val userMount = db.getUserMount(mountId)
        if (userMount != null) {
            return userMount.attachedLayerId
        }
        return if (db.getLayer(mountId) != null) mountId else null
    }

    private fun mountTargetsForLayer(layerId: String): List<String> {
        val targets = mutableListOf<String>()
        val layer = db.getLayer(layerId)
        if (layer != null) {
            targets.add(layer.id)
        }
        val mounts = db.listUserMountsByLayer(layerId)
        for (mount in mounts) {
            targets.add(mount.id)
        }
        return targets
    }

    private fun pendingInvalidationForMount(mountPath: String): AtomicInteger {
        return pendingInvalidationByMount.computeIfAbsent(mountPath) { AtomicInteger(0) }
    }

    private fun suppressionKey(op: NfsOp): String {
        val renameSuffix =
                if (op.kind == NfsOpKind.Rename) ":${op.targetRelativePath.orEmpty()}" else ""
        return "${op.mountId}:${op.relativePath}:${op.kind}$renameSuffix"
    }

    private fun isReplicationIgnoredOp(op: NfsOp): Boolean {
        if (isIgnoredReplicationPath(op.relativePath)) return true
        val target = op.targetRelativePath
        return target != null && isIgnoredReplicationPath(target)
    }

    private fun isIgnoredReplicationPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.any { part ->
            part.startsWith("._") ||
                    part.startsWith(".nfs") ||
                    part.startsWith(".spaces-reload-pulse.") ||
                    part == ".DS_Store"
        }
    }
}

@Serializable
data class ReplicationIdleSnapshot(
        val replayPending: Int,
        val invalidationPending: Int,
        val idle: Boolean
)
