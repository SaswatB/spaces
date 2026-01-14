package com.spaces.daemon

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class FileChangeType {
    Add,
    Modify,
    Delete
}

data class FileChange(val type: FileChangeType, val relativePath: String)

data class NfsOp(val mountId: String, val relativePath: String, val kind: NfsOpKind)

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

data class MountTarget(val mountId: String, val path: String)

class ReplicationService(private val db: SpacesDatabase, private val engine: ReplicationEngine) {
    fun handleOp(op: NfsOp) {
        val sourceRoot = mountPathForId(op.mountId) ?: return
        val layerId = resolveLayerId(op.mountId) ?: return
        val changeType =
                when (op.kind) {
                    NfsOpKind.Create,
                    NfsOpKind.Write,
                    NfsOpKind.Mkdir,
                    NfsOpKind.Setattr,
                    NfsOpKind.Rename -> FileChangeType.Modify
                    NfsOpKind.Remove, NfsOpKind.Rmdir -> FileChangeType.Delete
                }
        val change = FileChange(changeType, op.relativePath)
        val suppressionKey = "${op.mountId}:${op.relativePath}:${op.kind}"
        if (engine.isSuppressed(suppressionKey)) {
            return
        }
        val targetRoots = mountTargetsForLayer(layerId)
        for (target in targetRoots) {
            if (target.path == sourceRoot) continue
            val targetKey = "${target.mountId}:${op.relativePath}:${op.kind}"
            engine.suppress(targetKey)
            replayChange(change, Paths.get(sourceRoot), Paths.get(target.path))
        }
    }

    fun reconcileTrees(sourceRoot: Path, targetRoot: Path) {
        if (!Files.exists(sourceRoot)) return
        copyDirectoryContents(sourceRoot, targetRoot, sourceRoot)
        deleteRemovedEntries(sourceRoot, targetRoot)
    }

    private fun resolveLayerId(mountId: String): String? {
        val userMount = db.getUserMount(mountId)
        if (userMount != null) {
            return userMount.attachedLayerId
        }
        return if (db.getLayer(mountId) != null) mountId else null
    }

    private fun mountPathForId(mountId: String): String? {
        val userMount = db.getUserMount(mountId)
        if (userMount != null) {
            return userMount.mountPath
        }
        val layer = db.getLayer(mountId)
        return layer?.mountPath
    }

    private fun mountTargetsForLayer(layerId: String): List<MountTarget> {
        val targets = mutableListOf<MountTarget>()
        val layer = db.getLayer(layerId)
        if (layer != null) {
            targets.add(MountTarget(layer.id, layer.mountPath))
        }
        val mounts = db.listUserMountsByLayer(layerId)
        for (mount in mounts) {
            targets.add(MountTarget(mount.id, mount.mountPath))
        }
        return targets
    }

    private fun replayChange(change: FileChange, sourceRoot: Path, targetRoot: Path) {
        val sourcePath = sourceRoot.resolve(change.relativePath)
        val targetPath = targetRoot.resolve(change.relativePath)
        when (change.type) {
            FileChangeType.Add, FileChangeType.Modify -> copyPath(sourcePath, targetPath)
            FileChangeType.Delete -> deletePath(targetPath)
        }
    }

    private fun copyPath(source: Path, target: Path) {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target)
            applyMetadata(target, source)
            return
        }
        Files.createDirectories(target.parent)
        if (Files.exists(source)) {
            Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
            )
            applyMetadata(target, source)
        }
    }

    private fun deletePath(target: Path) {
        if (!Files.exists(target)) return
        if (Files.isDirectory(target)) {
            target.toFile().deleteRecursively()
        } else {
            Files.deleteIfExists(target)
        }
    }

    private fun copyDirectoryContents(source: Path, target: Path, root: Path) {
        if (!Files.exists(source)) return
        Files.newDirectoryStream(source).use { stream ->
            for (entry in stream) {
                val relative = root.relativize(entry)
                val targetPath = target.resolve(relative)
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(targetPath)
                    applyMetadata(targetPath, entry)
                    copyDirectoryContents(entry, target, root)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(
                            entry,
                            targetPath,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES
                    )
                    applyMetadata(targetPath, entry)
                }
            }
        }
    }

    private fun deleteRemovedEntries(sourceRoot: Path, targetRoot: Path) {
        if (!Files.exists(targetRoot)) return
        Files.newDirectoryStream(targetRoot).use { stream ->
            for (entry in stream) {
                val relative = targetRoot.relativize(entry)
                val sourcePath = sourceRoot.resolve(relative)
                if (!Files.exists(sourcePath)) {
                    deletePath(entry)
                } else if (Files.isDirectory(entry)) {
                    deleteRemovedEntries(sourcePath, entry)
                }
            }
        }
    }

    private fun applyMetadata(target: Path, source: Path) {
        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
        val perms =
                try {
                    Files.getPosixFilePermissions(source)
                } catch (_: Exception) {
                    null
                }
        if (perms != null) {
            Files.setPosixFilePermissions(target, perms)
        }
        val ownerAttrs =
                try {
                    Files.readAttributes(source, PosixFileAttributes::class.java)
                } catch (_: Exception) {
                    null
                }
        val view = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
        if (view != null) {
            view.setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime())
            if (ownerAttrs != null) {
                view.setOwner(ownerAttrs.owner())
                view.setGroup(ownerAttrs.group())
            }
        }
    }
}
