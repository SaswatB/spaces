package com.spaces.daemon

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
    private val replayDispatch = Executors.newSingleThreadExecutor()

    fun handleOp(op: NfsOp) {
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
            // Dispatch replay off the serving NFS thread.
            // Replay can touch mount paths to generate watcher-visible fs events, and we do not
            // want that I/O to synchronously re-enter NFS handling for the current request.
            replayDispatch.execute { runCatching { vfs.replay(op.mountId, targetMountId, op) } }
        }
    }

    fun handleLayerSwitchInvalidation(mountPath: String, oldLayerId: String?, newLayerId: String?) {
        vfs.invalidateMountForLayerSwitch(mountPath, oldLayerId, newLayerId)
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

    private fun suppressionKey(op: NfsOp): String {
        val renameSuffix =
                if (op.kind == NfsOpKind.Rename) ":${op.targetRelativePath.orEmpty()}" else ""
        return "${op.mountId}:${op.relativePath}:${op.kind}$renameSuffix"
    }
}
