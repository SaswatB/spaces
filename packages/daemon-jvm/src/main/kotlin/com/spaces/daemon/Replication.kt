package com.spaces.daemon

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
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

sealed class ViewChange {
    abstract val relativePath: String

    data class PathUpsert(override val relativePath: String) : ViewChange()

    data class DirectoryConverged(override val relativePath: String) : ViewChange()

    data class ViewInvalidated(
            override val relativePath: String,
            val targetRelativePath: String? = null,
            val rename: Boolean = false
    ) : ViewChange()
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
    private val replayQueuesByTarget = ConcurrentHashMap<String, ConcurrentLinkedQueue<ReplayWorkItem>>()
    private val replayDrainScheduledByTarget = ConcurrentHashMap<String, AtomicBoolean>()

    fun handleOp(op: NfsOp) {
        if (isReplicationIgnoredOp(op)) {
            return
        }
        val layerId = resolveLayerId(op.mountId) ?: return
        val suppressionKey = suppressionKey(op.mountId, changeFromOp(op))
        if (engine.isSuppressed(suppressionKey)) {
            return
        }
        val targetMountIds = mountTargetsForLayer(layerId)
        for (targetMountId in targetMountIds) {
            if (targetMountId == op.mountId) continue
            val changes = changesForTarget(op, targetMountId)
            for (change in changes) {
                engine.suppress(suppressionKey(targetMountId, change))
                enqueueReplay(
                        targetMountId,
                        ReplayWorkItem(op.mountId, targetMountId, change, System.nanoTime())
                )
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

    private fun enqueueReplay(targetMountId: String, item: ReplayWorkItem) {
        pendingReplayTasks.incrementAndGet()
        replayQueuesByTarget.computeIfAbsent(targetMountId) { ConcurrentLinkedQueue() }.add(item)
        scheduleReplayDrain(targetMountId)
    }

    private fun scheduleReplayDrain(targetMountId: String) {
        val scheduled =
                replayDrainScheduledByTarget.computeIfAbsent(targetMountId) { AtomicBoolean(false) }
        if (!scheduled.compareAndSet(false, true)) {
            return
        }
        // Dispatch replay off the serving NFS thread.
        // Replay can touch mount paths to generate watcher-visible fs events, and we do not want
        // that I/O to synchronously re-enter NFS handling for the current request.
        serialReplayDispatch.execute { drainReplayQueue(targetMountId, scheduled) }
    }

    private fun drainReplayQueue(targetMountId: String, scheduled: AtomicBoolean) {
        try {
            PerfStats.timed("replication.replay.batch") {
                val queue = replayQueuesByTarget[targetMountId] ?: return@timed
                while (true) {
                    val drained = drainPendingReplayBatch(queue)
                    if (drained.isEmpty()) break
                    try {
                        for (item in normalizeReplayBatch(drained)) {
                            PerfStats.observe(
                                    "replication.replay.queue_delay",
                                    System.nanoTime() - item.queuedAtNanos
                            )
                            val runStart = System.nanoTime()
                            if (shouldInvalidateSameLayerTarget(item)) {
                                suppressSyntheticInvalidationOps(item)
                            }
                            runCatching {
                                        if (shouldInvalidateSameLayerTarget(item)) {
                                            vfs.invalidateMountPath(item.targetMountId, item.change)
                                        } else {
                                            vfs.replay(
                                                    item.sourceMountId,
                                                    item.targetMountId,
                                                    item.change
                                            )
                                        }
                                    }
                                    .onFailure { error ->
                                        logger.warn(
                                                "Replay failed sourceMountId={} targetMountId={} change={} path={}",
                                                item.sourceMountId,
                                                item.targetMountId,
                                                item.change::class.simpleName,
                                                item.change.relativePath,
                                                error
                                        )
                                    }
                            PerfStats.observe(
                                    "replication.replay.task",
                                    System.nanoTime() - runStart
                            )
                        }
                    } finally {
                        pendingReplayTasks.addAndGet(-drained.size)
                    }
                }
            }
        } finally {
            scheduled.set(false)
            val queue = replayQueuesByTarget[targetMountId]
            if (queue != null && queue.isNotEmpty()) {
                scheduleReplayDrain(targetMountId)
            }
        }
    }

    private fun drainPendingReplayBatch(
            queue: ConcurrentLinkedQueue<ReplayWorkItem>
    ): List<ReplayWorkItem> {
        val drained = mutableListOf<ReplayWorkItem>()
        while (true) {
            val item = queue.poll() ?: break
            drained.add(item)
        }
        return drained
    }

    private fun normalizeReplayBatch(items: List<ReplayWorkItem>): List<ReplayWorkItem> {
        val latestByKey = LinkedHashMap<String, ReplayWorkItem>()
        for (item in items) {
            latestByKey[replayChangeKey(item)] = item
        }
        return latestByKey.values.toList()
    }

    private fun replayChangeKey(item: ReplayWorkItem): String {
        return "${item.sourceMountId}\n${changeKey(item.change)}"
    }

    private fun parentRelativePath(relativePath: String): String {
        if (relativePath.isBlank()) return ""
        val normalized = relativePath.trim('/')
        if (normalized.isEmpty()) return ""
        val slashIndex = normalized.lastIndexOf('/')
        return if (slashIndex < 0) "" else normalized.substring(0, slashIndex)
    }

    private fun shouldInvalidateSameLayerTarget(item: ReplayWorkItem): Boolean {
        val targetUserMount = db.getUserMount(item.targetMountId) ?: return false
        val sourceLayerId = underlyingLayerId(item.sourceMountId) ?: return false
        return targetUserMount.attachedLayerId == sourceLayerId
    }

    private fun suppressSyntheticInvalidationOps(item: ReplayWorkItem) {
        syntheticInvalidationChanges(item.change).forEach {
            engine.suppress(suppressionKey(item.targetMountId, it))
        }
    }

    private fun syntheticInvalidationChanges(change: ViewChange): List<ViewChange> {
        return when (change) {
            is ViewChange.PathUpsert -> listOf(ViewChange.ViewInvalidated(change.relativePath))
            is ViewChange.DirectoryConverged -> listOf(ViewChange.ViewInvalidated(change.relativePath))
            is ViewChange.ViewInvalidated ->
                    listOf(change, ViewChange.PathUpsert(change.relativePath))
        }
    }

    private fun underlyingLayerId(mountId: String): String? {
        val userMount = db.getUserMount(mountId)
        if (userMount != null) {
            return userMount.attachedLayerId
        }
        return if (db.getLayer(mountId) != null) mountId else null
    }

    private fun suppressionKey(mountId: String, change: ViewChange): String {
        return "$mountId:${changeKey(change)}"
    }

    private fun changeKey(change: ViewChange): String {
        return when (change) {
            is ViewChange.PathUpsert -> "upsert:${change.relativePath}"
            is ViewChange.DirectoryConverged -> "dir:${change.relativePath}"
            is ViewChange.ViewInvalidated ->
                    "invalidate:${change.relativePath}:${change.targetRelativePath.orEmpty()}:${change.rename}"
        }
    }

    private fun changeFromOp(op: NfsOp): ViewChange {
        return when (op.kind) {
            NfsOpKind.Create, NfsOpKind.Write, NfsOpKind.Setattr ->
                    ViewChange.PathUpsert(op.relativePath)
            NfsOpKind.Mkdir -> ViewChange.DirectoryConverged(op.relativePath)
            NfsOpKind.Remove, NfsOpKind.Rmdir ->
                    ViewChange.DirectoryConverged(parentRelativePath(op.relativePath))
            NfsOpKind.Rename ->
                    ViewChange.ViewInvalidated(
                            parentRelativePath(op.relativePath),
                            op.targetRelativePath,
                            rename = true
                    )
        }
    }

    private fun changesForTarget(op: NfsOp, targetMountId: String): List<ViewChange> {
        val sameLayer =
                db.getUserMount(targetMountId)?.attachedLayerId == resolveLayerId(op.mountId)
        if (sameLayer) {
            return when (op.kind) {
                NfsOpKind.Create, NfsOpKind.Write, NfsOpKind.Mkdir, NfsOpKind.Setattr ->
                        listOf(ViewChange.ViewInvalidated(op.relativePath))
                NfsOpKind.Remove, NfsOpKind.Rmdir ->
                        listOf(ViewChange.ViewInvalidated(parentRelativePath(op.relativePath)))
                NfsOpKind.Rename ->
                        listOf(
                                ViewChange.ViewInvalidated(
                                        parentRelativePath(op.relativePath),
                                        op.targetRelativePath,
                                        rename = true
                                )
                        )
            }
        }

        return when (op.kind) {
            NfsOpKind.Create, NfsOpKind.Write, NfsOpKind.Setattr ->
                    listOf(ViewChange.PathUpsert(op.relativePath))
            NfsOpKind.Mkdir -> listOf(ViewChange.DirectoryConverged(op.relativePath))
            NfsOpKind.Remove, NfsOpKind.Rmdir ->
                    listOf(ViewChange.DirectoryConverged(parentRelativePath(op.relativePath)))
            NfsOpKind.Rename -> {
                val changes = mutableListOf<ViewChange>()
                changes += ViewChange.DirectoryConverged(parentRelativePath(op.relativePath))
                op.targetRelativePath?.let { target ->
                    val toParent = parentRelativePath(target)
                    if (toParent != parentRelativePath(op.relativePath)) {
                        changes += ViewChange.DirectoryConverged(toParent)
                    }
                }
                changes
            }
        }
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
                    part.endsWith(".spaces-rename-notify") ||
                    part == ".DS_Store"
        }
    }
}

private data class ReplayWorkItem(
        val sourceMountId: String,
        val targetMountId: String,
        val change: ViewChange,
        val queuedAtNanos: Long
)

@Serializable
data class ReplicationIdleSnapshot(
        val replayPending: Int,
        val invalidationPending: Int,
        val idle: Boolean
)
