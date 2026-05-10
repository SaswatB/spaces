package com.spaces.daemon

import java.nio.file.Files
import java.nio.file.Path
import javax.security.auth.Subject
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import org.dcache.nfs.status.ExistException
import org.dcache.nfs.status.NoEntException
import org.dcache.nfs.status.NotEmptyException
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.stateid4
import org.dcache.nfs.vfs.Inode
import org.dcache.nfs.vfs.Stat
import org.dcache.nfs.vfs.VirtualFileSystem

class DaemonOverlayTest {
    @Test
    fun removingParentLayerFileCreatesWhiteoutInChildLayer() = withTestEnv { env ->
        env.writeEntrypointFile("shared.txt", "base")
        env.writeLayerFile("parent", "shared.txt", "parent")
        val layerRoot = env.layerRoot("child")

        env.vfs.remove(layerRoot, "shared.txt")

        assertTrue((env.layerUpper("child") / ".wh.shared.txt").exists())
        assertLookupMissing(layerRoot, "shared.txt", env.vfs)
    }

    @Test
    fun removingModifiedInheritedFileKeepsItDeleted() = withTestEnv { env ->
        env.writeEntrypointFile("shared.txt", "base")
        env.writeLayerFile("child", "shared.txt", "child")
        val layerRoot = env.layerRoot("child")

        env.vfs.remove(layerRoot, "shared.txt")

        assertFalse((env.layerUpper("child") / "shared.txt").exists())
        assertTrue((env.layerUpper("child") / ".wh.shared.txt").exists())
        assertLookupMissing(layerRoot, "shared.txt", env.vfs)
    }

    @Test
    fun renamingInheritedFileLeavesOldPathHidden() = withTestEnv { env ->
        env.writeEntrypointFile("old.txt", "base")
        val layerRoot = env.layerRoot("child")

        env.vfs.move(layerRoot, "old.txt", layerRoot, "new.txt")

        assertEquals("base", (env.layerUpper("child") / "new.txt").readText())
        assertTrue((env.layerUpper("child") / ".wh.old.txt").exists())
        assertLookupMissing(layerRoot, "old.txt", env.vfs)
        assertEquals("base", env.readVisibleFile(layerRoot, "new.txt"))
    }

    @Test
    fun creatingFileOverVisibleLowerPathFails() = withTestEnv { env ->
        env.writeEntrypointFile("existing.txt", "base")
        val layerRoot = env.layerRoot("child")

        try {
            env.vfs.create(layerRoot, Stat.Type.REGULAR, "existing.txt", Subject(), 0x1A4)
            fail("expected ExistException")
        } catch (_: ExistException) {
        }
    }

    @Test
    fun makingDirectoryOverVisibleLowerPathFails() = withTestEnv { env ->
        (env.entrypointRoot / "docs").createDirectories()
        val layerRoot = env.layerRoot("child")

        try {
            env.vfs.mkdir(layerRoot, "docs", Subject(), 0x1ED)
            fail("expected ExistException")
        } catch (_: ExistException) {
        }
    }

    @Test
    fun layerDiffUsesParentLayerView() = withTestEnv { env ->
        env.writeEntrypointFile("shared.txt", "base")
        env.writeLayerFile("parent", "shared.txt", "parent")
        env.writeLayerFile("child", "shared.txt", "child")

        val diff = env.service.layerDiff("child")

        assertEquals(1, diff.size)
        assertEquals("shared.txt", diff.single().path)
        assertEquals(LayerDiffType.Modify, diff.single().changeType)
    }

    @Test
    fun replayDirectoryViewRemovesStaleEntries() = withTestEnv { env ->
        (env.entrypointRoot / "dir").createDirectories()
        env.writeEntrypointFile("dir/keep.txt", "fresh")
        env.writeUserMountFile("mirror", "dir/stale.txt", "stale")
        env.writeUserMountFile("mirror", "dir/keep.txt", "old")

        env.vfs.replay("child", "mirror", ViewChange.DirectoryConverged("dir"))

        assertEquals("fresh", (env.userMountPath("mirror") / "dir/keep.txt").readText())
        assertFalse((env.userMountPath("mirror") / "dir/stale.txt").exists())
    }

    @Test
    fun sameLayerAttachedMountInvalidationDoesNotMaterializeContent() = withTestEnv { env ->
        env.writeLayerFile("child", "same-layer.txt", "fresh")

        env.replication.handleOp(NfsOp("child", "same-layer.txt", NfsOpKind.Write))
        assertTrue(env.replication.awaitIdle(5_000))

        assertFalse((env.userMountPath("mount") / "same-layer.txt").exists())
    }

    @Test
    fun removingNonEmptyInheritedDirectoryFails() = withTestEnv { env ->
        (env.entrypointRoot / "docs").createDirectories()
        env.writeEntrypointFile("docs/readme.md", "base")
        val layerRoot = env.layerRoot("child")

        assertFailsWith<NotEmptyException> { env.vfs.remove(layerRoot, "docs") }

        assertTrue(env.readVisibleFile(env.vfs.lookup(layerRoot, "docs"), "readme.md") == "base")
        assertFalse((env.layerUpper("child") / ".wh.docs").exists())
    }

    @Test
    fun removingEmptyInheritedDirectoryCreatesWhiteout() = withTestEnv { env ->
        (env.entrypointRoot / "empty-dir").createDirectories()
        val layerRoot = env.layerRoot("child")

        env.vfs.remove(layerRoot, "empty-dir")

        assertTrue((env.layerUpper("child") / ".wh.empty-dir").exists())
        assertLookupMissing(layerRoot, "empty-dir", env.vfs)
    }

    @Test
    fun brokenInheritedSymlinkIsVisible() = withTestEnv { env ->
        Files.createSymbolicLink(env.entrypointRoot / "broken-link", Path.of("missing-target"))
        val layerRoot = env.layerRoot("child")

        val inode = env.vfs.lookup(layerRoot, "broken-link")

        assertEquals("missing-target", env.vfs.readlink(inode))
    }

    @Test
    fun renamingInheritedSymlinkPreservesSymlink() = withTestEnv { env ->
        Files.createSymbolicLink(env.entrypointRoot / "link", Path.of("target.txt"))
        val layerRoot = env.layerRoot("child")

        env.vfs.move(layerRoot, "link", layerRoot, "renamed-link")

        val renamed = env.layerUpper("child") / "renamed-link"
        assertTrue(Files.isSymbolicLink(renamed))
        assertEquals("target.txt", Files.readSymbolicLink(renamed).toString())
        assertTrue((env.layerUpper("child") / ".wh.link").exists())
    }

    @Test
    fun replayDirectoryViewReplacesFileWithDirectory() = withTestEnv { env ->
        env.writeLayerFile("child", "shape/nested.txt", "fresh")
        env.writeUserMountFile("mirror", "shape", "old-file")

        env.vfs.replay("child", "mirror", ViewChange.DirectoryConverged(""))

        assertTrue(Files.isDirectory(env.userMountPath("mirror") / "shape"))
        assertEquals("fresh", (env.userMountPath("mirror") / "shape/nested.txt").readText())
    }

    @Test
    fun replayUpsertReplacesDirectoryWithFile() = withTestEnv { env ->
        env.writeLayerFile("child", "shape", "fresh-file")
        (env.userMountPath("mirror") / "shape").createDirectories()
        env.writeUserMountFile("mirror", "shape/stale.txt", "stale")

        env.vfs.replay("child", "mirror", ViewChange.PathUpsert("shape"))

        assertFalse(Files.isDirectory(env.userMountPath("mirror") / "shape"))
        assertEquals("fresh-file", (env.userMountPath("mirror") / "shape").readText())
    }

    @Test
    fun statefulWriteFlushesOnFirstWriteAndClose() = withTestEnv { env ->
        env.writeLayerFile("child", "stateful.txt", "old")
        val captured = mutableListOf<NfsOp>()
        env.vfs.setOpHandler { captured.add(it) }
        val inode = env.vfs.lookup(env.layerRoot("child"), "stateful.txt")
        val stateid = stateid4(ByteArray(12) { 7 }, 1)

        env.vfs.registerOpenState(inode, stateid, nfs4_prot.OPEN4_SHARE_ACCESS_WRITE)
        env.vfs.writeWithState(
                inode,
                stateid,
                "new".toByteArray(),
                0,
                3,
                VirtualFileSystem.StabilityLevel.UNSTABLE
        )

        assertEquals(1, captured.size)

        env.vfs.writeWithState(
                inode,
                stateid,
                "new!".toByteArray(),
                0,
                4,
                VirtualFileSystem.StabilityLevel.UNSTABLE
        )
        assertEquals(1, captured.size)

        env.vfs.closeOpenState(stateid)

        assertEquals(2, captured.size)
        assertTrue(captured.all { it.kind == NfsOpKind.Write })
        assertTrue(captured.all { it.relativePath == "stateful.txt" })
    }

    @Test
    fun statefulWriteFlushesDuringLongLivedHandle() = withTestEnv { env ->
        env.writeLayerFile("child", "stream.txt", "old")
        val captured = mutableListOf<NfsOp>()
        env.vfs.setOpHandler { captured.add(it) }
        val inode = env.vfs.lookup(env.layerRoot("child"), "stream.txt")
        val stateid = stateid4(ByteArray(12) { 3 }, 1)

        env.vfs.registerOpenState(inode, stateid, nfs4_prot.OPEN4_SHARE_ACCESS_WRITE)
        env.vfs.writeWithState(
                inode,
                stateid,
                "one".toByteArray(),
                0,
                3,
                VirtualFileSystem.StabilityLevel.UNSTABLE
        )
        assertTrue(captured.isNotEmpty())
        val firstCount = captured.size

        env.vfs.writeWithState(
                inode,
                stateid,
                "two".toByteArray(),
                0,
                3,
                VirtualFileSystem.StabilityLevel.UNSTABLE
        )
        assertEquals(firstCount, captured.size)

        Thread.sleep(120)
        env.vfs.writeWithState(
                inode,
                stateid,
                "tri".toByteArray(),
                0,
                3,
                VirtualFileSystem.StabilityLevel.UNSTABLE
        )
        assertTrue(captured.size > firstCount)

        env.vfs.closeOpenState(stateid)
    }

    @Test
    fun attachLayerRollsBackDatabaseStateWhenRemountFails() = withTestEnv { env ->
        val mountManager = FakeMountController()
        mountManager.markMounted(env.userMountRecord.mountPath, "/mounts/${env.userMountRecord.id}")
        mountManager.failEnsureOnceForPath = env.userMountRecord.mountPath
        val service = env.serviceWithMountController(mountManager)

        assertFailsWith<IllegalStateException> { service.attachLayer("mount", "parent") }

        val mount = env.db.getUserMount("mount") ?: fail("mount missing")
        assertEquals("child", mount.attachedLayerId)
        assertEquals(0, mount.generation)
        assertEquals("/mounts/mount", mountManager.mountedSourceByPath[env.userMountRecord.mountPath])
    }

    @Test
    fun attachLayerDoesNotMutateDatabaseWhenUnmountFails() = withTestEnv { env ->
        val mountManager = FakeMountController()
        mountManager.markMounted(env.userMountRecord.mountPath, "/mounts/${env.userMountRecord.id}")
        mountManager.failUnmountForPath = env.userMountRecord.mountPath
        val service = env.serviceWithMountController(mountManager)

        assertFailsWith<Exception> { service.attachLayer("mount", "parent") }

        val mount = env.db.getUserMount("mount") ?: fail("mount missing")
        assertEquals("child", mount.attachedLayerId)
        assertEquals(0, mount.generation)
        assertEquals("/mounts/mount", mountManager.mountedSourceByPath[env.userMountRecord.mountPath])
    }
}

private fun assertLookupMissing(parent: Inode, name: String, vfs: SpacesVfs) {
    try {
        vfs.lookup(parent, name)
        fail("expected NoEntException for $name")
    } catch (_: NoEntException) {
    }
}

private inline fun withTestEnv(block: (TestEnv) -> Unit) {
    val root = createTempDirectory("spaces-daemon-test")
    try {
        val env = TestEnv(root)
        block(env)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private class TestEnv(root: Path) {
    val dataDir: Path = root / "data"
    val entrypointRoot: Path = root / "entrypoint"
    private val dbPath: Path = root / "spaces.db"
    val db = SpacesDatabase(dbPath.toString())
    val vfs = SpacesVfs(db)
    val replication = ReplicationService(db, ReplicationEngine(), vfs)
    val service: SpacesService
    val userMountRecord: UserMountRecord
    val mirrorMountRecord: UserMountRecord

    init {
        db.initialize()
        dataDir.createDirectories()
        entrypointRoot.createDirectories()

        val config =
                Config(
                        dataDir = dataDir.toString(),
                        dbPath = dbPath.toString(),
                        apiHost = "127.0.0.1",
                        apiPort = 3100,
                        nfsHost = "127.0.0.1",
                        nfsPort = 11111
                )
        service = SpacesService(config, db, MountManager(config), replication, vfs)

        insertEntrypoint("ep", entrypointRoot)
        insertLayer("parent", "ep", null)
        insertLayer("child", "ep", "parent")
        userMountRecord = insertUserMount("mount", "ep", "child")
        mirrorMountRecord = insertUserMount("mirror", "ep", null)
    }

    fun layerRoot(layerId: String): Inode {
        val root = vfs.getRootInode()
        val layersDir = vfs.lookup(root, "layers")
        return vfs.lookup(layersDir, layerId)
    }

    fun layerUpper(layerId: String): Path = dataDir / "layers" / layerId / "upper"

    fun userMountPath(id: String): Path = dataDir / "mounts" / id

    fun writeEntrypointFile(relative: String, value: String) {
        val target = entrypointRoot / relative
        target.createParentDirectories()
        target.writeText(value)
    }

    fun writeLayerFile(layerId: String, relative: String, value: String) {
        val target = layerUpper(layerId) / relative
        target.createParentDirectories()
        target.writeText(value)
    }

    fun writeUserMountFile(id: String, relative: String, value: String) {
        val target = userMountPath(id) / relative
        target.createParentDirectories()
        target.writeText(value)
    }

    fun readVisibleFile(parent: Inode, name: String): String {
        val inode = vfs.lookup(parent, name)
        val stat = vfs.getattr(inode)
        val buffer = ByteArray(stat.size.toInt())
        val read = vfs.read(inode, buffer, 0, buffer.size)
        return buffer.copyOf(read).toString(Charsets.UTF_8)
    }

    fun serviceWithMountController(mountController: MountController): SpacesService {
        val config =
                Config(
                        dataDir = dataDir.toString(),
                        dbPath = dbPath.toString(),
                        apiHost = "127.0.0.1",
                        apiPort = 3100,
                        nfsHost = "127.0.0.1",
                        nfsPort = 11111
                )
        return SpacesService(config, db, mountController, replication, vfs)
    }

    private fun insertEntrypoint(id: String, path: Path) {
        db.insertEntrypoint(
                EntrypointRecord(
                        id = id,
                        name = id,
                        path = path.toString(),
                        createdAt = 0,
                        updatedAt = 0
                )
        )
    }

    private fun insertLayer(id: String, entrypointId: String, parentId: String?) {
        val root = dataDir / "layers" / id
        Files.createDirectories(root / "upper")
        Files.createDirectories(root / "work")
        Files.createDirectories(dataDir / "mounts" / id)
        db.insertLayer(
                LayerRecord(
                        id = id,
                        name = id,
                        entrypointId = entrypointId,
                        parentId = parentId,
                        upperDir = (root / "upper").toString(),
                        workDir = (root / "work").toString(),
                        mountPath = (dataDir / "mounts" / id).toString(),
                        createdAt = 0,
                        updatedAt = 0
                )
        )
    }

    private fun insertUserMount(id: String, entrypointId: String, layerId: String?): UserMountRecord {
        Files.createDirectories(dataDir / "mounts" / id)
        val record =
                UserMountRecord(
                        id = id,
                        name = id,
                        entrypointId = entrypointId,
                        attachedLayerId = layerId,
                        generation = 0,
                        mountPath = (dataDir / "mounts" / id).toString(),
                        createdAt = 0,
                        updatedAt = 0
        )
        db.insertUserMount(record)
        return record
    }
}

private class FakeMountController : MountController {
    val mountedSourceByPath = mutableMapOf<String, String>()
    var failUnmountForPath: String? = null
    var failEnsureForPath: String? = null
    var failEnsureOnceForPath: String? = null

    override fun ensureMount(exportPath: String, localPath: String) {
        if (mountedSourceByPath[localPath] != null && mountedSourceByPath[localPath] != exportPath) {
            unmount(localPath)
        }
        if (failEnsureOnceForPath == localPath) {
            failEnsureOnceForPath = null
            throw java.io.IOException("synthetic ensure failure")
        }
        if (failEnsureForPath == localPath) {
            throw java.io.IOException("synthetic ensure failure")
        }
        mountedSourceByPath[localPath] = exportPath
    }

    override fun unmount(localPath: String) {
        if (failUnmountForPath == localPath) {
            throw java.io.IOException("synthetic unmount failure")
        }
        mountedSourceByPath.remove(localPath)
    }

    override fun isMounted(localPath: String): Boolean = mountedSourceByPath.containsKey(localPath)

    fun markMounted(localPath: String, exportPath: String) {
        mountedSourceByPath[localPath] = exportPath
    }
}
