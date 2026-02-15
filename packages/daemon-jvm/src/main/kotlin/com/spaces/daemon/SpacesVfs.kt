package com.spaces.daemon

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.security.auth.Subject
import kotlin.io.path.exists
import org.dcache.nfs.status.ExistException
import org.dcache.nfs.status.InvalException
import org.dcache.nfs.status.NoEntException
import org.dcache.nfs.status.NotDirException
import org.dcache.nfs.status.NotEmptyException
import org.dcache.nfs.status.PermException
import org.dcache.nfs.v4.NfsIdMapping
import org.dcache.nfs.v4.Stateids
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfsace4
import org.dcache.nfs.v4.xdr.stateid4
import org.dcache.nfs.vfs.AclCheckable
import org.dcache.nfs.vfs.DirectoryEntry
import org.dcache.nfs.vfs.DirectoryStream as NfsDirectoryStream
import org.dcache.nfs.vfs.FsStat
import org.dcache.nfs.vfs.Inode
import org.dcache.nfs.vfs.Stat
import org.dcache.nfs.vfs.VirtualFileSystem
import org.slf4j.LoggerFactory

class SpacesVfs(private val db: SpacesDatabase) : VirtualFileSystem {
    private val logger = LoggerFactory.getLogger(SpacesVfs::class.java)
    private val overlay = OverlayEngine()
    private val idMapping = SimpleIdMapping()
    private val handleToPath = ConcurrentHashMap<String, String>()
    private val handleToInode = ConcurrentHashMap<String, Inode>()
    private val openWriteHandles = ConcurrentHashMap<String, OpenHandle>()
    @Volatile private var opHandler: ((NfsOp) -> Unit)? = null

    fun setOpHandler(handler: (NfsOp) -> Unit) {
        opHandler = handler
    }

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
        val start = System.nanoTime()
        logOpStart("CREATE", target.toString())
        try {
            when (type) {
                Stat.Type.DIRECTORY -> Files.createDirectory(target)
                Stat.Type.SYMLINK -> throw IOException("symlink create not supported via create")
                else -> {
                    Files.createDirectories(target.parent)
                    Files.createFile(target)
                }
            }
            applyMode(target, mode)
        } catch (_: FileAlreadyExistsException) {
            throw ExistException()
        } finally {
            logOpEnd("CREATE", target.toString(), start)
        }
        overlay.applyEntrypointOwner(mount.view, target)
        emitOp(mount, relative, NfsOpKind.Create)
        return inodeForResolvedPath(resolved.mountPath, relative, target)
    }

    override fun getFsStat(): FsStat = FsStat(0, 0, 0, 0)

    override fun getRootInode(): Inode = inodeForVirtualPath("/", "")

    override fun lookup(parent: Inode, path: String): Inode {
        val resolved = resolveNode(parent)
        requireKnown(resolved)
        return when (resolved.kind) {
            NodeKind.EXPORT_ROOT ->
                    when (path) {
                        "layers" -> inodeForVirtualPath("/", "layers")
                        "mounts" -> inodeForVirtualPath("/", "mounts")
                        else -> throw NoEntException()
                    }
            NodeKind.LAYERS_DIR -> {
                val layerId = validatePathComponent(path)
                if (db.getLayer(layerId) == null) throw NoEntException()
                inodeForVirtualPath("/", prefixPath("layers/$layerId"))
            }
            NodeKind.MOUNTS_DIR -> {
                val mountId = validatePathComponent(path)
                if (db.getUserMount(mountId) == null) throw NoEntException()
                inodeForVirtualPath("/", prefixPath("mounts/$mountId"))
            }
            NodeKind.LAYER_ROOT, NodeKind.MOUNT_ROOT, NodeKind.OVERLAY -> {
                val mount = resolved.mountView ?: throw NoEntException()
                val relative = resolveChildRelative(resolved, path)
                val resolvedPath =
                        overlay.resolvePath(mount.view, relative) ?: throw NoEntException()
                inodeForResolvedPath(resolved.mountPath, relative, resolvedPath.source)
            }
            else -> throw NoEntException()
        }
    }

    override fun link(parent: Inode, inode: Inode, name: String, subject: Subject): Inode {
        val parentResolved = resolveNode(parent)
        requireKnown(parentResolved)
        val mount = parentResolved.mountView ?: throw NotDirException()
        ensureWritable(mount)

        val sourceResolved = resolveNode(inode)
        requireKnown(sourceResolved)
        val sourceMount = sourceResolved.mountView ?: throw NoEntException()
        if (sourceMount.mountId != mount.mountId) throw PermException()
        if (sourceResolved.kind != NodeKind.OVERLAY) throw PermException()

        val sourceRel = sourceResolved.relativePath
        val targetRel = resolveChildRelative(parentResolved, name)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        overlay.copyUpIfNeeded(mount.view, sourceRel)
        overlay.ensureParentDirs(mount.view, Paths.get(targetRel))
        val sourcePath = topUpper.resolve(sourceRel)
        val targetPath = topUpper.resolve(targetRel)
        if (Files.isDirectory(sourcePath)) throw PermException()
        Files.createLink(targetPath, sourcePath)
        overlay.applyEntrypointOwner(mount.view, targetPath)
        emitOp(mount, targetRel, NfsOpKind.Create)
        return inodeForResolvedPath(parentResolved.mountPath, targetRel, targetPath)
    }

    override fun list(dir: Inode, verifier: ByteArray, cookie: Long): NfsDirectoryStream {
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

            val dirEntries = ArrayList<DirectoryEntry>(entries.size)
            var index = 0L
            for (name in entries) {
                index += 1
                if (index <= cookie) {
                    continue
                }
                val inode = lookup(dir, name)
                val stat = getattr(inode)
                dirEntries.add(DirectoryEntry(name, inode, stat, index))
            }
            return NfsDirectoryStream(verifier, dirEntries)
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

    override fun directoryVerifier(dir: Inode): ByteArray {
        val resolved = resolveNode(dir)
        requireKnown(resolved)
        return try {
            when (resolved.kind) {
                NodeKind.EXPORT_ROOT -> verifierFromString("export_root")
                NodeKind.LAYERS_DIR -> verifierFromEntries(db.listLayerIds())
                NodeKind.MOUNTS_DIR -> verifierFromEntries(db.listUserMountIds())
                NodeKind.LAYER_ROOT, NodeKind.MOUNT_ROOT, NodeKind.OVERLAY -> {
                    val mount = resolved.mountView ?: return NfsDirectoryStream.ZERO_VERIFIER
                    val rel = resolved.relativePath
                    val candidates = ArrayList<String>()
                    val roots = mount.view.layers + mount.view.entrypoint
                    for (root in roots) {
                        val dir = root.resolve(rel)
                        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) continue
                        val attrs =
                                Files.readAttributes(
                                        dir,
                                        BasicFileAttributes::class.java,
                                        LinkOption.NOFOLLOW_LINKS
                                )
                        val key = stableKeyForPath(dir, followLinks = false)
                        candidates.add(
                                "dir:${key ?: dir}:${attrs.lastModifiedTime().toMillis()}:${attrs.size()}"
                        )
                    }
                    if (candidates.isEmpty()) NfsDirectoryStream.ZERO_VERIFIER
                    else verifierFromString(candidates.sorted().joinToString("|"))
                }
                else -> NfsDirectoryStream.ZERO_VERIFIER
            }
        } catch (_: Exception) {
            NfsDirectoryStream.ZERO_VERIFIER
        }
    }

    override fun mkdir(parent: Inode, name: String, subject: Subject, mode: Int): Inode {
        val resolved = resolveNode(parent)
        val mount = resolved.mountView ?: throw NotDirException()
        ensureWritable(mount)
        val relative = resolveChildRelative(resolved, name)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        overlay.ensureParentDirs(mount.view, Paths.get(relative))
        val target = topUpper.resolve(relative)
        Files.createDirectories(target)
        applyMode(target, mode)
        overlay.applyEntrypointOwner(mount.view, target)
        overlay.markOpaque(mount.view, relative)
        emitOp(mount, relative, NfsOpKind.Mkdir)
        return inodeForResolvedPath(resolved.mountPath, relative, target)
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
        val start = System.nanoTime()
        logOpStart("MOVE", "${source} -> ${target}")
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOpEnd("MOVE", "${source} -> ${target}", start)
        updateHandlePathsForMove(fromResolved.mountPath, fromRel, toRel)
        emitOp(mount, fromRel, NfsOpKind.Rename, toRel)
        return true
    }

    override fun parentOf(inode: Inode): Inode {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        return when (resolved.kind) {
            NodeKind.EXPORT_ROOT -> inodeForVirtualPath("/", "")
            NodeKind.LAYERS_DIR, NodeKind.MOUNTS_DIR -> inodeForVirtualPath("/", "")
            NodeKind.LAYER_ROOT -> inodeForVirtualPath("/", prefixPath("layers"))
            NodeKind.MOUNT_ROOT -> inodeForVirtualPath("/", prefixPath("mounts"))
            NodeKind.OVERLAY -> {
                val mount = resolved.mountView ?: throw NoEntException()
                val parentRel = Paths.get(resolved.relativePath).parent?.toString() ?: ""
                if (parentRel.isEmpty()) {
                    inodeForVirtualPath(resolved.mountPath, "")
                } else {
                    val parentPath = overlay.resolvePath(mount.view, parentRel)
                    if (parentPath != null) {
                        inodeForResolvedPath(resolved.mountPath, parentRel, parentPath.source)
                    } else {
                        inodeForVirtualPath(resolved.mountPath, parentRel)
                    }
                }
            }
            else -> inodeForVirtualPath("/", "")
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
        val target = Files.readSymbolicLink(resolvedPath.source)
        if (target.isAbsolute) {
            val entrypoint = mount.view.entrypoint.normalize()
            val normalizedTarget = target.normalize()
            if (normalizedTarget.startsWith(entrypoint)) {
                val relativeTarget = entrypoint.relativize(normalizedTarget)
                return Paths.get(resolved.mountPath).resolve(relativeTarget).toString()
            }
        }
        return target.toString()
    }

    override fun remove(parent: Inode, name: String) {
        val resolved = resolveNode(parent)
        val mount = resolved.mountView ?: throw NotDirException()
        ensureWritable(mount)
        val rel = resolveChildRelative(resolved, name)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        val upperPath = topUpper.resolve(rel)
        if (upperPath.exists()) {
            val start = System.nanoTime()
            logOpStart("REMOVE", upperPath.toString())
            try {
                Files.deleteIfExists(upperPath)
            } catch (_: DirectoryNotEmptyException) {
                throw NotEmptyException()
            } finally {
                logOpEnd("REMOVE", upperPath.toString(), start)
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
        return inodeForResolvedPath(resolved.mountPath, rel, linkPath)
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
        val start = System.nanoTime()
        logOpStart("WRITE", target.toString())
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
                    logOpEnd("WRITE", target.toString(), start)
                    emitOp(mount, rel, NfsOpKind.Write)
                    return VirtualFileSystem.WriteResult(stabilityLevel, written)
                }
    }

    fun writeWithState(
            inode: Inode,
            stateid: stateid4,
            data: ByteArray,
            offset: Long,
            count: Int,
            stabilityLevel: VirtualFileSystem.StabilityLevel
    ): VirtualFileSystem.WriteResult {
        if (Stateids.isStateLess(stateid)) {
            return write(inode, data, offset, count, stabilityLevel)
        }

        val resolved = resolveNode(inode)
        requireKnown(resolved)
        val mount = resolved.mountView ?: throw NoEntException()
        ensureWritable(mount)
        val rel = resolved.relativePath
        val key = stateKey(stateid)
        val handle = openWriteHandles[key]
        if (handle != null) {
            val start = System.nanoTime()
            logOpStart("WRITE", handle.path.toString())
            return try {
                val buffer = ByteBuffer.wrap(data, 0, count)
                val written = handle.channel.write(buffer, offset)
                logOpEnd("WRITE", handle.path.toString(), start)
                emitOp(mount, rel, NfsOpKind.Write)
                VirtualFileSystem.WriteResult(stabilityLevel, written)
            } catch (_: ClosedChannelException) {
                openWriteHandles.remove(stateKey(stateid))
                write(inode, data, offset, count, stabilityLevel)
            }
        }

        return write(inode, data, offset, count, stabilityLevel)
    }

    fun registerOpenState(inode: Inode, stateid: stateid4, shareAccess: Int) {
        val access = shareAccess and nfs4_prot.OPEN4_SHARE_ACCESS_BOTH
        if (access and nfs4_prot.OPEN4_SHARE_ACCESS_WRITE == 0) return

        val key = stateKey(stateid)
        if (openWriteHandles.containsKey(key)) return

        val target = resolveWritableTarget(inode)
        val channel = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.READ)
        val existing = openWriteHandles.putIfAbsent(key, OpenHandle(channel, target))
        if (existing != null) {
            channel.close()
        }
    }

    fun downgradeOpenState(stateid: stateid4, shareAccess: Int) {
        val access = shareAccess and nfs4_prot.OPEN4_SHARE_ACCESS_BOTH
        if (access and nfs4_prot.OPEN4_SHARE_ACCESS_WRITE != 0) return
        closeOpenState(stateid)
    }

    fun closeOpenState(stateid: stateid4) {
        val handle = openWriteHandles.remove(stateKey(stateid)) ?: return
        try {
            handle.channel.close()
        } catch (e: Exception) {
            logger.warn(
                    "Failed to close open state stateid={} path={}",
                    stateKey(stateid),
                    handle.path,
                    e
            )
        }
    }

    override fun commit(inode: Inode, offset: Long, count: Int) {
        val resolved = resolveNode(inode)
        val rel = resolved.relativePath
        val start = System.nanoTime()
        logOpStart("COMMIT", rel)
        logOpEnd("COMMIT", rel, start)
    }

    override fun getattr(inode: Inode): Stat {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        return try {
            when (resolved.kind) {
                NodeKind.EXPORT_ROOT, NodeKind.LAYERS_DIR, NodeKind.MOUNTS_DIR -> {
                    dirStat(resolved.path)
                }
                NodeKind.LAYER_ROOT, NodeKind.MOUNT_ROOT -> {
                    val mount = resolved.mountView
                    if (mount != null) {
                        virtualDirStat(resolved.path, mount.view.entrypoint)
                    } else {
                        dirStat(resolved.path)
                    }
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
        if (!stat.isDefined(Stat.StatAttribute.SIZE) && !stat.isDefined(Stat.StatAttribute.MODE))
                return

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
        // Accept and ignore ACL updates; macOS clients often send these.
    }

    override fun hasIOLayout(inode: Inode): Boolean = false

    override fun getAclCheckable(): AclCheckable = AclCheckable.ALLOW_ALL

    override fun getIdMapper(): NfsIdMapping = idMapping

    private fun inodeForVirtualPath(root: String, relative: String): Inode {
        val path =
                if (relative.isBlank()) root else root.trimEnd('/') + "/" + relative.trimStart('/')
        val handleBytes = hashBytes("virtual:$path")
        return inodeForHandle(handleBytes, path)
    }

    private fun inodeForResolvedPath(root: String, relative: String, sourcePath: Path?): Inode {
        val path =
                if (relative.isBlank()) root else root.trimEnd('/') + "/" + relative.trimStart('/')
        val handleBytes =
                if (sourcePath != null) handleForPath(sourcePath, followLinks = false)
                else hashBytes("virtual:$path")
        return inodeForHandle(handleBytes, path)
    }

    private fun inodeForHandle(handleBytes: ByteArray, path: String): Inode {
        val handleKey = handleKey(handleBytes)
        handleToPath[handleKey] = path
        return handleToInode.computeIfAbsent(handleKey) { Inode.forFile(handleBytes) }
    }

    private fun pathFor(inode: Inode): String {
        val handleKey = handleKey(inode.fileId)
        return handleToPath[handleKey] ?: ""
    }

    private fun hashBytes(value: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
    }

    private fun fileIdForKey(key: String): Long {
        val digest = hashBytes(key)
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        return value
    }

    private fun handleKey(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun handleForPath(path: Path, followLinks: Boolean): ByteArray {
        val key =
                stableKeyForPath(path, followLinks) ?: path.toAbsolutePath().normalize().toString()
        return hashBytes("file:$key")
    }

    private fun stableFileId(path: Path, followLinks: Boolean): Long {
        val key =
                stableKeyForPath(path, followLinks) ?: path.toAbsolutePath().normalize().toString()
        return fileIdForKey("file:$key")
    }

    private fun stableKeyForPath(path: Path, followLinks: Boolean): String? {
        val options =
                if (followLinks) emptyArray<LinkOption>() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        try {
            val dev = Files.getAttribute(path, "unix:dev", *options)
            val ino = Files.getAttribute(path, "unix:ino", *options)
            if (dev is Number && ino is Number) {
                return "${dev.toLong()}:${ino.toLong()}"
            }
        } catch (_: Exception) {
            // ignore
        }
        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, *options)
            attrs.fileKey()?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun updateHandlePathsForMove(root: String, fromRelative: String, toRelative: String) {
        val oldPath =
                if (fromRelative.isBlank()) root
                else root.trimEnd('/') + "/" + fromRelative.trimStart('/')
        val newPath =
                if (toRelative.isBlank()) root
                else root.trimEnd('/') + "/" + toRelative.trimStart('/')
        handleToPath.forEach { (handleKey, path) ->
            if (path == oldPath || path.startsWith("$oldPath/")) {
                handleToPath[handleKey] = newPath + path.removePrefix(oldPath)
            }
        }
    }

    private fun verifierFromEntries(entries: List<String>): ByteArray {
        val combined = entries.sorted().joinToString("\u0000")
        return verifierFromString(combined)
    }

    private fun verifierFromString(value: String): ByteArray {
        val hash = hashBytes(value)
        return hash.copyOf(NfsDirectoryStream.ZERO_VERIFIER.size)
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
        val component = validatePathComponent(name)
        return if (parent.relativePath.isBlank()) component
        else parent.relativePath.trimEnd('/') + "/" + component
    }

    private fun validatePathComponent(name: String): String {
        if (name.isBlank()) throw InvalException()
        if (name == "." || name == "..") throw InvalException()
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw InvalException()
        }
        return name
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
        stat.setFileid(fileIdForKey("virtual:$path"))
        stat.setGeneration(0)
        return stat
    }

    private fun virtualDirStat(exportPath: String, source: Path): Stat {
        val stat = Stat()
        val attrs =
                Files.readAttributes(
                        source,
                        PosixFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS
                )
        stat.setMode(Stat.S_IFDIR or permsToMode(attrs.permissions()))
        stat.setNlink(2)
        stat.setUid(readUnixId(source, "uid"))
        stat.setGid(readUnixId(source, "gid"))
        stat.setSize(attrs.size())
        stat.setATime(attrs.lastAccessTime().toMillis())
        stat.setMTime(attrs.lastModifiedTime().toMillis())
        stat.setCTime(attrs.creationTime().toMillis())
        stat.setFileid(fileIdForKey("virtual:$exportPath"))
        stat.setGeneration(0)
        return stat
    }

    private fun fileStat(path: Path): Stat {
        val stat = Stat()
        val attrs =
                Files.readAttributes(
                        path,
                        PosixFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS
                )
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
        stat.setFileid(stableFileId(path, followLinks = false))
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

    private fun applyMode(target: Path, mode: Int) {
        try {
            Files.setPosixFilePermissions(target, modeToPermissions(mode))
        } catch (e: Exception) {
            logger.warn("Failed to apply mode to target={} mode={}", target, mode, e)
        }
    }

    private fun resolveWritableTarget(inode: Inode): Path {
        val resolved = resolveNode(inode)
        requireKnown(resolved)
        val mount = resolved.mountView ?: throw NoEntException()
        ensureWritable(mount)
        val rel = resolved.relativePath
        overlay.copyUpIfNeeded(mount.view, rel)
        val topUpper = mount.view.layers.firstOrNull() ?: throw PermException()
        val target = topUpper.resolve(rel)
        Files.createDirectories(target.parent)
        return target
    }

    private fun stateKey(stateid: stateid4): String {
        return Base64.getEncoder().encodeToString(stateid.other)
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

    private fun emitOp(
            mount: MountView,
            relative: String,
            kind: NfsOpKind,
            targetRelative: String? = null
    ) {
        opHandler?.invoke(
                NfsOp(
                        mountId = mount.mountId,
                        relativePath = relative,
                        kind = kind,
                        targetRelativePath = targetRelative
                )
        )
    }

    private fun logOpStart(op: String, path: String) {
        logger.debug("{} start path={}", op, path)
    }

    private fun logOpEnd(op: String, path: String, start: Long) {
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        logger.debug("{} end path={} durationMs={}", op, path, elapsedMs)
    }

    // region: Layer switch invalidation

    fun invalidateMountForLayerSwitch(mountPath: String, oldLayerId: String?, newLayerId: String?) {
        val mountRoot = Paths.get(mountPath)
        if (!Files.exists(mountRoot)) return

        val oldFingerprints = dirtyFingerprintsForLayer(oldLayerId)
        val newFingerprints = dirtyFingerprintsForLayer(newLayerId)
        val changed = mutableListOf<String>()
        for (path in oldFingerprints.keys + newFingerprints.keys) {
            if (oldFingerprints[path] != newFingerprints[path]) {
                changed.add(path)
            }
        }

        if (changed.isNotEmpty()) {
            val now = FileTime.from(Instant.now())
            for (relative in changed.sorted()) {
                val target = mountRoot.resolve(relative)
                if (Files.exists(target)) {
                    runCatching { Files.setLastModifiedTime(target, now) }
                }
                val parent = target.parent
                if (parent != null && Files.exists(parent)) {
                    runCatching { Files.setLastModifiedTime(parent, now) }
                }
            }
        }

        // Keep a deterministic mount-visible rename/touch event for watchers.
        emitSwitchMarkerEvent(mountRoot)
    }

    private fun dirtyFingerprintsForLayer(layerId: String?): Map<String, String> {
        if (layerId == null) return emptyMap()
        val mountView = buildViewForLayer(layerId) ?: return emptyMap()
        val dirtyPaths = dirtyPathsForLayer(layerId)
        val result = mutableMapOf<String, String>()
        for (relative in dirtyPaths) {
            if (relative.isBlank()) continue
            result[relative] = fingerprintPath(mountView.view, relative)
        }
        return result
    }

    private fun dirtyPathsForLayer(layerId: String): Set<String> {
        val layer = db.getLayer(layerId) ?: return emptySet()
        val entrypoint = db.getEntrypoint(layer.entrypointId) ?: return emptySet()
        val upperRoot = Paths.get(layer.upperDir)
        val entryRoot = Paths.get(entrypoint.path)
        val entries = mutableSetOf<String>()
        if (Files.exists(upperRoot)) {
            collectDirtyPaths(upperRoot, upperRoot, entryRoot, entries)
        }
        return entries
    }

    private fun collectDirtyPaths(
            upperRoot: Path,
            current: Path,
            entryRoot: Path,
            entries: MutableSet<String>
    ) {
        Files.newDirectoryStream(current).use { stream ->
            for (entry in stream) {
                val rel = upperRoot.relativize(entry)
                val name = entry.fileName.toString()
                if (name == OPAQUE_MARKER) continue
                val whiteout = isWhiteoutMarker(name)
                if (whiteout != null) {
                    val deletePath = rel.parent?.resolve(whiteout) ?: Paths.get(whiteout)
                    entries.add(deletePath.toString())
                    continue
                }

                val entrypointPath = entryRoot.resolve(rel)
                if (Files.exists(entrypointPath)) {
                    entries.add(rel.toString())
                } else {
                    entries.add(rel.toString())
                }
                if (Files.isDirectory(entry)) {
                    collectDirtyPaths(upperRoot, entry, entryRoot, entries)
                }
            }
        }
    }

    private fun fingerprintPath(view: OverlayView, relative: String): String {
        val resolved = overlay.resolvePath(view, relative) ?: return "missing"
        val source = resolved.source
        return when {
            Files.isDirectory(source) -> {
                val entries = overlay.listDir(view, relative).sorted().joinToString("\n")
                "dir:${sha256Bytes(entries.toByteArray())}"
            }
            Files.isSymbolicLink(source) -> {
                val target =
                        runCatching { Files.readSymbolicLink(source).toString() }.getOrDefault("")
                "symlink:$target"
            }
            else -> "file:${sha256File(source)}"
        }
    }

    private fun emitSwitchMarkerEvent(mountRoot: Path) {
        if (!Files.exists(mountRoot)) return
        val now = FileTime.from(Instant.now())
        val marker = mountRoot.resolve(".spaces-hot-reload")
        val temp = mountRoot.resolve(".spaces-hot-reload.${UUID.randomUUID()}")
        runCatching {
            Files.writeString(
                    temp,
                    now.toMillis().toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )
            Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(marker, now)
            Files.setLastModifiedTime(mountRoot, now)
        }
                .onFailure {
                    runCatching {
                        if (!Files.exists(marker)) {
                            Files.createFile(marker)
                        }
                        Files.setLastModifiedTime(marker, now)
                    }
                }
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // endregion

    // region: Replay

    fun replay(sourceMountId: String, targetMountId: String, op: NfsOp) {
        // Source is resolved through the in-process overlay model so replay reads never traverse
        // exported NFS mount paths (avoids self-reentrant NFS reads during replication).
        val sourceMount = buildMountViewById(sourceMountId) ?: return
        // Target writes intentionally go through the target mount path to surface real filesystem
        // change notifications for external watchers (dev servers, hot-reload tooling).
        val targetRoot = mountPathForId(targetMountId) ?: return

        when (op.kind) {
            NfsOpKind.Remove, NfsOpKind.Rmdir ->
                    replayDelete(Paths.get(targetRoot), op.relativePath)
            NfsOpKind.Rename -> {
                val toRelative = op.targetRelativePath ?: return
                val sourcePath = overlay.resolvePath(sourceMount.view, toRelative)?.source
                replayRename(
                        sourceMount,
                        sourcePath,
                        Paths.get(targetRoot),
                        op.relativePath,
                        toRelative
                )
            }
            NfsOpKind.Create, NfsOpKind.Write, NfsOpKind.Mkdir, NfsOpKind.Setattr ->
                    replayUpsert(sourceMount, Paths.get(targetRoot), op.relativePath)
        }
    }

    private fun buildMountViewById(mountId: String): MountView? {
        return buildViewForUserMount(mountId) ?: buildViewForLayer(mountId)
    }

    private fun mountPathForId(mountId: String): String? {
        val userMount = db.getUserMount(mountId)
        if (userMount != null) return userMount.mountPath
        val layer = db.getLayer(mountId)
        return layer?.mountPath
    }

    private fun replayUpsert(sourceMount: MountView, targetRoot: Path, relative: String) {
        val source = overlay.resolvePath(sourceMount.view, relative)?.source
        if (source == null) return
        val target = targetRoot.resolve(relative)
        if (Files.isDirectory(source)) {
            replayDirectoryView(sourceMount, targetRoot, relative)
            return
        }
        copyFile(source, target)
    }

    private fun replayDelete(targetRoot: Path, relative: String) {
        val target = targetRoot.resolve(relative)
        deletePath(target)
    }

    private fun replayRename(
            sourceMount: MountView,
            sourcePath: Path?,
            targetRoot: Path,
            fromRelative: String,
            toRelative: String
    ) {
        val targetFrom = targetRoot.resolve(fromRelative)
        val targetTo = targetRoot.resolve(toRelative)
        if (targetFrom != targetTo && Files.exists(targetFrom)) {
            val parent = targetTo.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            runCatching { Files.move(targetFrom, targetTo, StandardCopyOption.REPLACE_EXISTING) }
                    .onSuccess {
                        return
                    }
        }

        if (sourcePath != null && Files.exists(sourcePath)) {
            replayUpsert(sourceMount, targetRoot, toRelative)
        }
        if (targetFrom != targetTo) {
            deletePath(targetFrom)
        }
    }

    private fun replayDirectoryView(sourceMount: MountView, targetRoot: Path, relative: String) {
        // Enumerate the directory through overlay view semantics, not a single concrete dir.
        // This preserves parent/lower visibility when the top upper is only a partial delta.
        val targetDir = targetRoot.resolve(relative)
        Files.createDirectories(targetDir)
        val entries = overlay.listDir(sourceMount.view, relative)
        for (name in entries) {
            val childRelative = if (relative.isBlank()) name else "$relative/$name"
            val childSource =
                    overlay.resolvePath(sourceMount.view, childRelative)?.source ?: continue
            val childTarget = targetRoot.resolve(childRelative)
            if (Files.isDirectory(childSource)) {
                replayDirectoryView(sourceMount, targetRoot, childRelative)
            } else {
                copyFile(childSource, childTarget)
            }
        }
    }

    private fun copyFile(source: Path, target: Path) {
        if (!Files.exists(source) || Files.isDirectory(source)) return
        val parent = target.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.copy(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        )
    }

    private fun deletePath(target: Path) {
        if (!Files.exists(target)) return
        if (Files.isDirectory(target)) {
            target.toFile().deleteRecursively()
        } else {
            Files.deleteIfExists(target)
        }
    }

    // endregion
}

private data class OpenHandle(val channel: FileChannel, val path: Path)

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
