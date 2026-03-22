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
import kotlin.test.assertTrue
import kotlin.test.fail
import org.dcache.nfs.status.ExistException
import org.dcache.nfs.status.NoEntException
import org.dcache.nfs.vfs.Inode
import org.dcache.nfs.vfs.Stat

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
        env.writeUserMountFile("mount", "dir/stale.txt", "stale")
        env.writeUserMountFile("mount", "dir/keep.txt", "old")

        env.vfs.replay("child", "mount", NfsOp("child", "dir", NfsOpKind.Mkdir))

        assertEquals("fresh", (env.userMountPath("mount") / "dir/keep.txt").readText())
        assertFalse((env.userMountPath("mount") / "dir/stale.txt").exists())
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
        service = SpacesService(config, db, MountManager(config), replication)

        insertEntrypoint("ep", entrypointRoot)
        insertLayer("parent", "ep", null)
        insertLayer("child", "ep", "parent")
        insertUserMount("mount", "ep", "child")
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

    private fun insertUserMount(id: String, entrypointId: String, layerId: String?) {
        val root = dataDir / "usermounts" / id
        Files.createDirectories(root / "upper")
        Files.createDirectories(root / "work")
        Files.createDirectories(dataDir / "mounts" / id)
        db.insertUserMount(
                UserMountRecord(
                        id = id,
                        name = id,
                        entrypointId = entrypointId,
                        attachedLayerId = layerId,
                        upperDir = (root / "upper").toString(),
                        workDir = (root / "work").toString(),
                        mountPath = (dataDir / "mounts" / id).toString(),
                        createdAt = 0,
                        updatedAt = 0
                )
        )
    }
}
