package com.spaces.daemon

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.security.auth.Subject
import kotlin.io.path.exists
import org.dcache.nfs.status.NoEntException
import org.dcache.nfs.status.NotDirException
import org.dcache.nfs.status.NotEmptyException
import org.dcache.nfs.status.PermException
import org.dcache.nfs.v4.NfsIdMapping
import org.dcache.nfs.v4.xdr.nfsace4
import org.dcache.nfs.vfs.AclCheckable
import org.dcache.nfs.vfs.DirectoryEntry
import org.dcache.nfs.vfs.DirectoryStream as NfsDirectoryStream
import org.dcache.nfs.vfs.FsStat
import org.dcache.nfs.vfs.Inode
import org.dcache.nfs.vfs.Stat
import org.dcache.nfs.vfs.VirtualFileSystem
import org.slf4j.LoggerFactory

class SpacesVfs(
        private val db: SpacesDatabase,
        private val replication: ReplicationService? = null
) : VirtualFileSystem {
    private val logger = LoggerFactory.getLogger(SpacesVfs::class.java)
    private val overlay = OverlayEngine()
    private val idMapping = SimpleIdMapping()
    private val inodeCache = ConcurrentHashMap<String, Inode>()

    override fun access(inode: Inode, mode: Int): Int {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        return mode
    }

    override fun create(
            parent: Inode,
            type: Stat.Type,
            name: String,
            subject: Subject,
            mode: Int
    ): Inode {
        val resolved = resolveNode(parent)
        val mount = resolved.mountView ?: throw NotDirException()
        ensureWritable(mount)
        val relative = resolveChildRelative(resolved, name)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        overlay.ensureParentDirs(mount.view, Paths.get(relative))
        val target = topUpper.resolve(relative)
        when (type) {
            Stat.Type.DIRECTORY -> Files.createDirectories(target)
            Stat.Type.SYMLINK -> throw IOException("symlink create not supported via create")
            else -> {
                Files.createDirectories(target.parent)
                Files.createFile(target)
            }
        }
        overlay.applyEntrypointOwner(mount.view, target)
        emitOp(mount, relative, NfsOpKind.Create)
        return inodeForPath(resolved.mountPath, relative)
    }

    override fun getFsStat(): FsStat = FsStat(0, 0, 0, 0)

    override fun getRootInode(): Inode = inodeForPath("/", "")

    override fun lookup(parent: Inode, path: String): Inode {
        val resolved = resolveNode(parent)
        requireKnown(resolved)
        return when (resolved.kind) {
            NodeKind.EXPORT_ROOT ->
                    when (path) {
                        "layers" -> inodeForPath("/", "layers")
                        "mounts" -> inodeForPath("/", "mounts")
                        else -> throw NoEntException()
                    }
            NodeKind.LAYERS_DIR -> {
                if (db.getLayer(path) == null) throw NoEntException()
                inodeForPath("/", prefixPath("layers/$path"))
            }
            NodeKind.MOUNTS_DIR -> {
                if (db.getUserMount(path) == null) throw NoEntException()
                inodeForPath("/", prefixPath("mounts/$path"))
            }
            NodeKind.LAYER_ROOT, NodeKind.MOUNT_ROOT, NodeKind.OVERLAY -> {
                val mount = resolved.mountView ?: throw NoEntException()
                val relative = resolveChildRelative(resolved, path)
                overlay.resolvePath(mount.view, relative) ?: throw NoEntException()
                inodeForPath(resolved.mountPath, relative)
            }
            else -> throw NoEntException()
        }
    }

    override fun link(parent: Inode, inode: Inode, name: String, subject: Subject): Inode {
        throw IOException("link not supported")
    }

    override fun list(dir: Inode, cookie: ByteArray, verifier: Long): NfsDirectoryStream {
        val resolved = resolveNode(dir)
        requireKnown(resolved)
        try {
            val entries =
                    when (resolved.kind) {
                        NodeKind.EXPORT_ROOT -> listOf("layers", "mounts")
                        NodeKind.LAYERS_DIR -> db.listLayerIds()
                        NodeKind.MOUNTS_DIR -> db.listUserMountIds()
                        NodeKind.LAYER_ROOT, NodeKind.MOUNT_ROOT, NodeKind.OVERLAY -> {
                            val mount = resolved.mountView ?: throw NotDirException()
                            overlay.listDir(mount.view, resolved.relativePath)
                        }
                        else -> throw NotDirException()
                    }

            val dirEntries =
                    entries.mapIndexed { index, name ->
                        val inode = lookup(dir, name)
                        val stat = getattr(inode)
                        DirectoryEntry(name, inode, stat, index.toLong() + 1)
                    }
            return NfsDirectoryStream(dirEntries)
        } catch (error: Exception) {
            logger.warn(
                    "VFS list failed path={} kind={} rel={} mountId={}",
                    resolved.path,
                    resolved.kind,
                    resolved.relativePath,
                    resolved.mountId,
                    error
            )
            throw error
        }
    }

    override fun directoryVerifier(dir: Inode): ByteArray = NfsDirectoryStream.ZERO_VERIFIER

    override fun mkdir(parent: Inode, name: String, subject: Subject, mode: Int): Inode {
        val resolved = resolveNode(parent)
        val mount = resolved.mountView ?: throw NotDirException()
        ensureWritable(mount)
        val relative = resolveChildRelative(resolved, name)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        overlay.ensureParentDirs(mount.view, Paths.get(relative))
        val target = topUpper.resolve(relative)
        Files.createDirectories(target)
        overlay.applyEntrypointOwner(mount.view, target)
        overlay.markOpaque(mount.view, relative)
        emitOp(mount, relative, NfsOpKind.Mkdir)
        return inodeForPath(resolved.mountPath, relative)
    }

    override fun move(from: Inode, oldName: String, to: Inode, newName: String): Boolean {
        val fromResolved = resolveNode(from)
        val toResolved = resolveNode(to)
        val mount = fromResolved.mountView ?: throw NotDirException()
        if (toResolved.mountView?.mountId != mount.mountId) {
            throw IOException("cross-mount rename not supported")
        }
        ensureWritable(mount)
        val fromRel = resolveChildRelative(fromResolved, oldName)
        val toRel = resolveChildRelative(toResolved, newName)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        overlay.copyUpIfNeeded(mount.view, fromRel)
        overlay.ensureParentDirs(mount.view, Paths.get(toRel))
        val source = topUpper.resolve(fromRel)
        val target = topUpper.resolve(toRel)
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        emitOp(mount, fromRel, NfsOpKind.Rename)
        return true
    }

    override fun parentOf(inode: Inode): Inode {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        return when (resolved.kind) {
            NodeKind.EXPORT_ROOT -> inodeForPath("/", "")
            NodeKind.LAYERS_DIR, NodeKind.MOUNTS_DIR -> inodeForPath("/", "")
            NodeKind.LAYER_ROOT -> inodeForPath("/", prefixPath("layers"))
            NodeKind.MOUNT_ROOT -> inodeForPath("/", prefixPath("mounts"))
            NodeKind.OVERLAY -> {
                if (resolved.relativePath.isEmpty()) {
                    inodeForPath(resolved.mountPath, "")
                } else {
                    val parentRel = Paths.get(resolved.relativePath).parent?.toString() ?: ""
                    inodeForPath(resolved.mountPath, parentRel)
                }
            }
            else -> inodeForPath("/", "")
        }
    }

    override fun read(inode: Inode, data: ByteArray, offset: Long, count: Int): Int {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        val mount = resolved.mountView ?: throw NoEntException()
        val rel = resolved.relativePath
        val resolvedPath = overlay.resolvePath(mount.view, rel) ?: throw NoEntException()
        FileChannel.open(resolvedPath.source, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.wrap(data, 0, count)
            channel.position(offset)
            return channel.read(buffer)
        }
    }

    override fun readlink(inode: Inode): String {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        val mount = resolved.mountView ?: throw NoEntException()
        val rel = resolved.relativePath
        val resolvedPath = overlay.resolvePath(mount.view, rel) ?: throw NoEntException()
        return Files.readSymbolicLink(resolvedPath.source).toString()
    }

    override fun remove(parent: Inode, name: String) {
        val resolved = resolveNode(parent)
        val mount = resolved.mountView ?: throw NotDirException()
        ensureWritable(mount)
        val rel = resolveChildRelative(resolved, name)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        val upperPath = topUpper.resolve(rel)
        if (upperPath.exists()) {
            try {
                Files.deleteIfExists(upperPath)
            } catch (_: DirectoryNotEmptyException) {
                throw NotEmptyException()
            }
            emitOp(mount, rel, NfsOpKind.Remove)
            return
        }
        val lower = mount.view.entrypoint.resolve(rel)
        if (lower.exists()) {
            overlay.markWhiteout(mount.view, rel)
            emitOp(mount, rel, NfsOpKind.Remove)
            return
        }
        throw NoEntException()
    }

    override fun symlink(
            parent: Inode,
            linkName: String,
            target: String,
            subject: Subject,
            mode: Int
    ): Inode {
        val resolved = resolveNode(parent)
        val mount = resolved.mountView ?: throw NotDirException()
        ensureWritable(mount)
        val rel = resolveChildRelative(resolved, linkName)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        overlay.ensureParentDirs(mount.view, Paths.get(rel))
        val linkPath = topUpper.resolve(rel)
        Files.createSymbolicLink(linkPath, Paths.get(target))
        overlay.applyEntrypointOwner(mount.view, linkPath)
        emitOp(mount, rel, NfsOpKind.Create)
        return inodeForPath(resolved.mountPath, rel)
    }

    override fun write(
            inode: Inode,
            data: ByteArray,
            offset: Long,
            count: Int,
            stabilityLevel: VirtualFileSystem.StabilityLevel
    ): VirtualFileSystem.WriteResult {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        val mount = resolved.mountView ?: throw NoEntException()
        ensureWritable(mount)
        val rel = resolved.relativePath
        overlay.copyUpIfNeeded(mount.view, rel)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        val target = topUpper.resolve(rel)
        Files.createDirectories(target.parent)
        FileChannel.open(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.READ
                )
                .use { channel ->
                    channel.position(offset)
                    val buffer = ByteBuffer.wrap(data, 0, count)
                    val written = channel.write(buffer)
                    emitOp(mount, rel, NfsOpKind.Write)
                    return VirtualFileSystem.WriteResult(stabilityLevel, written)
                }
    }

    override fun commit(inode: Inode, offset: Long, count: Int) {
        // no-op
    }

    override fun getattr(inode: Inode): Stat {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        return try {
            when (resolved.kind) {
                NodeKind.EXPORT_ROOT,
                NodeKind.LAYERS_DIR,
                NodeKind.MOUNTS_DIR,
                NodeKind.LAYER_ROOT,
                NodeKind.MOUNT_ROOT -> {
                    dirStat(resolved.path)
                }
                NodeKind.OVERLAY -> {
                    val mount = resolved.mountView ?: throw NoEntException()
                    val rel = resolved.relativePath
                    val resolvedPath =
                            overlay.resolvePath(mount.view, rel) ?: throw NoEntException()
                    fileStat(resolvedPath.source)
                }
                else -> dirStat(resolved.path)
            }
        } catch (error: Exception) {
            logger.warn(
                    "VFS getattr failed path={} kind={} rel={} mountId={}",
                    resolved.path,
                    resolved.kind,
                    resolved.relativePath,
                    resolved.mountId,
                    error
            )
            throw error
        }
    }

    override fun setattr(inode: Inode, stat: Stat) {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        val mount = resolved.mountView ?: throw NoEntException()
        ensureWritable(mount)
        val rel = resolved.relativePath
        overlay.copyUpIfNeeded(mount.view, rel)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        val target = topUpper.resolve(rel)

        if (stat.isDefined(Stat.StatAttribute.SIZE)) {
            FileChannel.open(target, StandardOpenOption.WRITE).use { channel ->
                channel.truncate(stat.size)
            }
        }

        if (stat.isDefined(Stat.StatAttribute.MODE)) {
            try {
                Files.setPosixFilePermissions(target, modeToPermissions(stat.mode))
            } catch (_: Exception) {
                // ignore
            }
        }
        emitOp(mount, rel, NfsOpKind.Setattr)
    }

    override fun getAcl(inode: Inode): Array<nfsace4> = emptyArray()

    override fun setAcl(inode: Inode, acl: Array<nfsace4>) {
        throw IOException("ACL not supported")
    }

    override fun hasIOLayout(inode: Inode): Boolean = false

    override fun getAclCheckable(): AclCheckable = AclCheckable.ALLOW_ALL

    override fun getIdMapper(): NfsIdMapping = idMapping

    private fun inodeForPath(root: String, relative: String): Inode {
        val path =
                if (relative.isBlank()) root else root.trimEnd('/') + "/" + relative.trimStart('/')
        return inodeCache.computeIfAbsent(path) { Inode.forFile(path.toByteArray(Charsets.UTF_8)) }
    }

    private fun pathFor(inode: Inode): String {
        return String(inode.fileId, Charsets.UTF_8)
    }

    private fun resolveNode(inode: Inode): ResolvedNode {
        val path = pathFor(inode)
        if (!path.startsWith("/")) return ResolvedNode(NodeKind.UNKNOWN, path, null, null, "", "")
        val relative = path.removePrefix("/").trimStart('/')
        if (relative.isEmpty()) return ResolvedNode(NodeKind.EXPORT_ROOT, path, null, null, "", "/")
        val segments = relative.split('/').filter { it.isNotBlank() }.toMutableList()
        if (segments.isEmpty()) return ResolvedNode(NodeKind.EXPORT_ROOT, path, null, null, "", "/")
        val top = segments[0]
        return when (top) {
            "layers" -> resolveLayerPath(path, segments)
            "mounts" -> resolveMountPath(path, segments)
            else -> ResolvedNode(NodeKind.UNKNOWN, path, null, null, "", "/")
        }
    }

    private fun resolveLayerPath(fullPath: String, segments: List<String>): ResolvedNode {
        if (segments.size == 1)
                return ResolvedNode(NodeKind.LAYERS_DIR, fullPath, null, null, "", "/")
        val layerId = segments[1]
        val mountPath = "/layers/$layerId"
        val mountView =
                buildViewForLayer(layerId)
                        ?: return ResolvedNode(NodeKind.UNKNOWN, fullPath, null, null, "", "/")
        return if (segments.size == 2) {
            ResolvedNode(NodeKind.LAYER_ROOT, fullPath, mountView, layerId, "", mountPath)
        } else {
            val rel = segments.drop(2).joinToString("/")
            ResolvedNode(NodeKind.OVERLAY, fullPath, mountView, layerId, rel, mountPath)
        }
    }

    private fun resolveMountPath(fullPath: String, segments: List<String>): ResolvedNode {
        if (segments.size == 1) {
            return ResolvedNode(NodeKind.MOUNTS_DIR, fullPath, null, null, "", "/")
        }
        val mountId = segments[1]
        val mountPath = "/mounts/$mountId"
        val mountView =
                buildViewForUserMount(mountId)
                        ?: return ResolvedNode(NodeKind.UNKNOWN, fullPath, null, null, "", "/")
        return if (segments.size == 2) {
            ResolvedNode(NodeKind.MOUNT_ROOT, fullPath, mountView, mountId, "", mountPath)
        } else {
            val rel = segments.drop(2).joinToString("/")
            ResolvedNode(NodeKind.OVERLAY, fullPath, mountView, mountId, rel, mountPath)
        }
    }

    private fun buildViewForLayer(layerId: String): MountView? {
        val layer = db.getLayer(layerId) ?: return null
        val entrypoint = db.getEntrypoint(layer.entrypointId) ?: return null
        val layers = mutableListOf<Path>()
        var current: LayerRecord? = layer
        while (current != null) {
            layers.add(Paths.get(current.upperDir))
            current = current.parentId?.let { db.getLayer(it) }
        }
        return MountView(
                view = OverlayView(Paths.get(entrypoint.path), layers),
                writable = true,
                mountId = layerId
        )
    }

    private fun buildViewForUserMount(mountId: String): MountView? {
        val mount = db.getUserMount(mountId) ?: return null
        val layerId = mount.attachedLayerId
        if (layerId != null) {
            val layer = db.getLayer(layerId) ?: return null
            val entrypoint = db.getEntrypoint(layer.entrypointId) ?: return null
            val layers = mutableListOf<Path>()
            var current: LayerRecord? = layer
            while (current != null) {
                layers.add(Paths.get(current.upperDir))
                current = current.parentId?.let { db.getLayer(it) }
            }
            return MountView(
                    view = OverlayView(Paths.get(entrypoint.path), layers),
                    writable = true,
                    mountId = mountId
            )
        }
        val entrypoint = db.getEntrypoint(mount.entrypointId) ?: return null
        return MountView(
                view = OverlayView(Paths.get(entrypoint.path), emptyList()),
                writable = false,
                mountId = mountId
        )
    }

    private fun ensureWritable(mount: MountView) {
        if (!mount.writable) throw PermException()
    }

    private fun requireKnown(node: ResolvedNode) {
        if (node.kind == NodeKind.UNKNOWN) throw NoEntException()
    }

    private fun resolveChildRelative(parent: ResolvedNode, name: String): String {
        return if (parent.relativePath.isBlank()) name
        else parent.relativePath.trimEnd('/') + "/" + name
    }

    private fun prefixPath(relative: String): String {
        val trimmed = relative.trimStart('/')
        return "/$trimmed"
    }

    private fun dirStat(path: String): Stat {
        val stat = Stat()
        val now = Instant.now().toEpochMilli()
        stat.setMode(Stat.S_IFDIR or 0x1ED)
        stat.setNlink(2)
        val pathValue = Paths.get(path)
        stat.setUid(readUnixId(pathValue, "uid"))
        stat.setGid(readUnixId(pathValue, "gid"))
        stat.setSize(0)
        stat.setATime(now)
        stat.setMTime(now)
        stat.setCTime(now)
        stat.setFileid(path.hashCode().toLong())
        stat.setGeneration(0)
        return stat
    }

    private fun fileStat(path: Path): Stat {
        val stat = Stat()
        val attrs = Files.readAttributes(path, PosixFileAttributes::class.java)
        val mode =
                if (attrs.isDirectory) Stat.S_IFDIR
                else if (attrs.isSymbolicLink) Stat.S_IFLNK else Stat.S_IFREG
        stat.setMode(mode or permsToMode(attrs.permissions()))
        stat.setNlink(1)
        stat.setUid(readUnixId(path, "uid"))
        stat.setGid(readUnixId(path, "gid"))
        stat.setSize(attrs.size())
        stat.setATime(attrs.lastAccessTime().toMillis())
        stat.setMTime(attrs.lastModifiedTime().toMillis())
        stat.setCTime(attrs.creationTime().toMillis())
        stat.setFileid(path.toString().hashCode().toLong())
        stat.setGeneration(0)
        return stat
    }

    private fun permsToMode(perms: Set<PosixFilePermission>): Int {
        var mode = 0
        if (perms.contains(PosixFilePermission.OWNER_READ)) mode = mode or 0x100
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) mode = mode or 0x80
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) mode = mode or 0x40
        if (perms.contains(PosixFilePermission.GROUP_READ)) mode = mode or 0x20
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) mode = mode or 0x10
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) mode = mode or 0x8
        if (perms.contains(PosixFilePermission.OTHERS_READ)) mode = mode or 0x4
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) mode = mode or 0x2
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) mode = mode or 0x1
        return mode
    }

    private fun modeToPermissions(mode: Int): Set<PosixFilePermission> {
        val bits = mode and 0x1FF
        val str = buildString {
            append(if (bits and 0x100 != 0) 'r' else '-')
            append(if (bits and 0x80 != 0) 'w' else '-')
            append(if (bits and 0x40 != 0) 'x' else '-')
            append(if (bits and 0x20 != 0) 'r' else '-')
            append(if (bits and 0x10 != 0) 'w' else '-')
            append(if (bits and 0x8 != 0) 'x' else '-')
            append(if (bits and 0x4 != 0) 'r' else '-')
            append(if (bits and 0x2 != 0) 'w' else '-')
            append(if (bits and 0x1 != 0) 'x' else '-')
        }
        return PosixFilePermissions.fromString(str)
    }

    private fun readUnixId(path: Path, name: String): Int {
        return try {
            val value = Files.getAttribute(path, "unix:$name")
            when (value) {
                is Number -> value.toInt()
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun emitOp(mount: MountView, relative: String, kind: NfsOpKind) {
        replication?.handleOp(NfsOp(mount.mountId, relative, kind))
    }
}

data class MountView(val view: OverlayView, val writable: Boolean, val mountId: String)

enum class NodeKind {
    EXPORT_ROOT,
    LAYERS_DIR,
    MOUNTS_DIR,
    LAYER_ROOT,
    MOUNT_ROOT,
    OVERLAY,
    UNKNOWN
}

data class ResolvedNode(
        val kind: NodeKind,
        val path: String,
        val mountView: MountView?,
        val mountId: String?,
        val relativePath: String,
        val mountPath: String
)

class SimpleIdMapping : NfsIdMapping {
    override fun principalToUid(principal: String): Int = principal.toIntOrNull() ?: 0

    override fun principalToGid(principal: String): Int = principal.toIntOrNull() ?: 0

    override fun uidToPrincipal(uid: Int): String = uid.toString()

    override fun gidToPrincipal(gid: Int): String = gid.toString()
}
