package com.spaces.daemon

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MountManagerTest {
    @Test
    fun ensureMountRemountsWhenExistingMountUsesWrongSource() {
        val root = createTempDirectory("spaces-mount-manager-test")
        try {
            val localPath = (root / "mount").createDirectories().toString()
            val calls = mutableListOf<List<String>>()
            var mountOutput =
                    """
                    old-host:/mounts/other on $localPath (nfs, nodev, nosuid)
                    """.trimIndent()
            val manager =
                    MountManager(testConfig(root)) { command, _ ->
                        calls.add(command)
                        when {
                            command == listOf("mount") -> CommandResult(0, mountOutput, "")
                            command == listOf("umount", localPath) -> {
                                mountOutput = ""
                                CommandResult(0, "", "")
                            }
                            command.firstOrNull() == "mount_nfs" -> {
                                mountOutput = "127.0.0.1:/mounts/target on $localPath (nfs, nodev, nosuid)"
                                CommandResult(0, "", "")
                            }
                            else -> CommandResult(0, "", "")
                        }
                    }

            manager.ensureMount("/mounts/target", localPath)

            assertEquals(listOf("mount"), calls[0])
            val umountIndex = calls.indexOfFirst { it == listOf("umount", localPath) }
            val mountNfsIndex = calls.indexOfFirst { it.firstOrNull() == "mount_nfs" }
            assertTrue(umountIndex > 0)
            assertTrue(mountNfsIndex > umountIndex)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun testConfig(root: Path): Config {
        return Config(
                dataDir = (root / "data").toString(),
                dbPath = (root / "spaces.db").toString(),
                apiHost = "127.0.0.1",
                apiPort = 3100,
                nfsHost = "127.0.0.1",
                nfsPort = 11111
        )
    }
}
