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
        val suppressionKey = suppressionKey(op)
        if (engine.isSuppressed(suppressionKey)) {
            return
        }
        val targetMountIds = mountTargetsForLayer(layerId)
        for (targetMountId in targetMountIds) {
            if (targetMountId == op.mountId) continue
            val targetKey = suppressionKey(op.copy(mountId = targetMountId))
            engine.suppress(targetKey)
            enqueueReplay(targetMountId, ReplayWorkItem(op.mountId, targetMountId, op, System.nanoTime()))
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
                                        vfs.invalidateMountPath(item.targetMountId, item.op)
                                    } else {
                                        vfs.replay(item.sourceMountId, item.targetMountId, item.op)
                                    }
                                }
                                .onFailure { error ->
                                    logger.warn(
                                            "Replay failed sourceMountId={} targetMountId={} opKind={} path={} targetPath={}",
                                            item.sourceMountId,
                                            item.targetMountId,
                                            item.op.kind,
                                            item.op.relativePath,
                                            item.op.targetRelativePath,
                                            error
                                    )
                                }
                        PerfStats.observe("replication.replay.task", System.nanoTime() - runStart)
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
        if (drained.isNotEmpty()) {
            pendingReplayTasks.addAndGet(-drained.size)
        }
        return drained
    }

    private fun normalizeReplayBatch(items: List<ReplayWorkItem>): List<ReplayWorkItem> {
        val upserts = mutableListOf<ReplayWorkItem>()
        val lastUpsertIndexByPath = mutableMapOf<String, Int>()
        val reconcileParents = LinkedHashMap<String, ReplayWorkItem>()
        val sameLayerInvalidations = LinkedHashMap<String, ReplayWorkItem>()
        for (item in items) {
            if (shouldInvalidateSameLayerTarget(item)) {
                sameLayerInvalidations[sameLayerInvalidationKey(item)] = item
                continue
            }
            val key = replayPathKey(item)
            when (item.op.kind) {
                NfsOpKind.Create, NfsOpKind.Write, NfsOpKind.Mkdir, NfsOpKind.Setattr -> {
                    val existingIndex = lastUpsertIndexByPath[key]
                    if (existingIndex != null) {
                        upserts[existingIndex] = item
                    } else {
                        upserts.add(item)
                        lastUpsertIndexByPath[key] = upserts.lastIndex
                    }
                }
                NfsOpKind.Remove, NfsOpKind.Rmdir -> {
                    lastUpsertIndexByPath.remove(key)
                    reconcileParents[parentRelativePath(item.op.relativePath)] =
                            replayDirectoryItem(item, parentRelativePath(item.op.relativePath))
                }
                NfsOpKind.Rename -> {
                    lastUpsertIndexByPath.remove(key)
                    item.op.targetRelativePath?.let { target ->
                        lastUpsertIndexByPath.remove(replayPathKey(item, target))
                    }
                    val fromParent = parentRelativePath(item.op.relativePath)
                    reconcileParents[fromParent] = replayDirectoryItem(item, fromParent)
                    val toParent = parentRelativePath(item.op.targetRelativePath.orEmpty())
                    reconcileParents[toParent] = replayDirectoryItem(item, toParent)
                }
            }
        }
        val normalized = mutableListOf<ReplayWorkItem>()
        normalized.addAll(upserts)
        normalized.addAll(reconcileParents.values)
        normalized.addAll(sameLayerInvalidations.values)
        return normalized
    }

    private fun replayPathKey(
            item: ReplayWorkItem,
            relativePath: String = item.op.relativePath
    ): String {
        return "${item.sourceMountId}\n$relativePath"
    }

    private fun replayDirectoryItem(item: ReplayWorkItem, relativePath: String): ReplayWorkItem {
        return item.copy(op = NfsOp(item.op.mountId, relativePath, NfsOpKind.Mkdir))
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

    private fun sameLayerInvalidationKey(item: ReplayWorkItem): String {
        return when (item.op.kind) {
            NfsOpKind.Remove, NfsOpKind.Rmdir ->
                    "parent:${item.targetMountId}:${parentRelativePath(item.op.relativePath)}"
            NfsOpKind.Rename ->
                    "rename:${item.targetMountId}:${item.op.relativePath}:${item.op.targetRelativePath.orEmpty()}"
            else -> "path:${item.targetMountId}:${item.op.relativePath}"
        }
    }

    private fun suppressSyntheticInvalidationOps(item: ReplayWorkItem) {
        syntheticInvalidationOps(item).forEach { engine.suppress(suppressionKey(it)) }
    }

    private fun syntheticInvalidationOps(item: ReplayWorkItem): List<NfsOp> {
        val mountId = item.targetMountId
        return when (item.op.kind) {
            NfsOpKind.Create, NfsOpKind.Write, NfsOpKind.Mkdir, NfsOpKind.Setattr ->
                    listOf(NfsOp(mountId, item.op.relativePath, NfsOpKind.Setattr))
            NfsOpKind.Remove, NfsOpKind.Rmdir ->
                    listOf(
                            NfsOp(
                                    mountId,
                                    parentRelativePath(item.op.relativePath),
                                    NfsOpKind.Setattr
                            )
                    )
            NfsOpKind.Rename -> {
                val ops = mutableListOf<NfsOp>()
                ops += NfsOp(mountId, parentRelativePath(item.op.relativePath), NfsOpKind.Setattr)
                item.op.targetRelativePath?.let { target ->
                    ops += NfsOp(mountId, parentRelativePath(target), NfsOpKind.Setattr)
                    ops += NfsOp(mountId, target, NfsOpKind.Setattr)
                    val scratch = renameScratchRelativePath(target)
                    ops += NfsOp(mountId, target, NfsOpKind.Rename, scratch)
                    ops += NfsOp(mountId, scratch, NfsOpKind.Rename, target)
                }
                ops
            }
        }
    }

    private fun renameScratchRelativePath(relativePath: String): String {
        val normalized = relativePath.trim('/')
        if (normalized.isBlank()) return ".spaces-rename-notify"
        val path = java.nio.file.Paths.get(normalized)
        val scratchName = ".${path.fileName}.spaces-rename-notify"
        val parent = path.parent
        return parent?.resolve(scratchName)?.toString() ?: scratchName
    }

    private fun underlyingLayerId(mountId: String): String? {
        val userMount = db.getUserMount(mountId)
        if (userMount != null) {
            return userMount.attachedLayerId
        }
        return if (db.getLayer(mountId) != null) mountId else null
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

private data class ReplayWorkItem(
        val sourceMountId: String,
        val targetMountId: String,
        val op: NfsOp,
        val queuedAtNanos: Long
)

@Serializable
data class ReplicationIdleSnapshot(
        val replayPending: Int,
        val invalidationPending: Int,
        val idle: Boolean
)
