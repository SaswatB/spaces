package com.spaces.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory

interface MountController {
    fun ensureMount(exportPath: String, localPath: String)

    fun unmount(localPath: String)

    fun isMounted(localPath: String): Boolean
}

class MountManager(
        private val config: Config,
        private val commandRunner: ((List<String>, Duration?) -> CommandResult)? = null
) : MountController {
    private val logger = LoggerFactory.getLogger(MountManager::class.java)

    override fun ensureMount(exportPath: String, localPath: String) {
        logger.info("Ensuring mount for $localPath")
        val expectedSource = "${config.nfsHost}:$exportPath"
        val currentSource = mountedSource(localPath)
        if (currentSource != null) {
            if (currentSource == expectedSource) {
                logger.info("Mount for $localPath already exists")
                return
            }
            logger.info(
                    "Mount for $localPath exists with unexpected source $currentSource, remounting to $expectedSource"
            )
            unmount(localPath)
        }

        Files.createDirectories(Paths.get(localPath))
        val options =
                listOf("vers=4", "tcp", "port=${config.nfsPort}", "soft", "timeo=10", "retrans=2")
                        .joinToString(",")
        val result =
                runCommand(listOf("mount_nfs", "-o", options, expectedSource, localPath), 20.seconds)
        if (result.exitCode != 0)
                throw IOException("mount_nfs failed for $localPath: ${result.stderr}")
        if (mountedSource(localPath) != expectedSource)
                throw IOException("mount_nfs succeeded but $localPath not mounted")
    }

    override fun unmount(localPath: String) {
        logger.info("Unmounting $localPath")
        if (!isMounted(localPath)) {
            logger.info("Mount for $localPath not found")
            return
        }

        val result = runCommand(listOf("umount", localPath), 10.seconds)
        if (result.exitCode == 0) return

        logger.info(
                "Unmount for $localPath failed, attempting force unmount, stderr: ${result.stderr}"
        )
        val forced = runCommand(listOf("umount", "-f", localPath), 10.seconds)
        if (forced.exitCode == 0) return

        logger.info(
                "Force unmount for $localPath failed, attempting diskutil unmount force, stderr: ${forced.stderr}"
        )
        val diskutil = runCommand(listOf("diskutil", "unmount", "force", localPath), 10.seconds)
        if (diskutil.exitCode == 0) return

        logger.error(
                "Unmount for $localPath failed, attempting force unmount and diskutil unmount force failed, stderr: ${diskutil.stderr}"
        )
        throw IOException("umount failed for $localPath")
    }

    override fun isMounted(localPath: String): Boolean = mountedSource(localPath) != null

    fun mountedSource(localPath: String): String? {
        val output = runCommand(listOf("mount"))
        if (output.exitCode != 0) return null

        val canonical = File(localPath).canonicalPath
        output.stdout.lineSequence().forEach { line ->
            val source = line.substringBefore(" on ", "")
            val mountPoint = line.split(" on ").getOrNull(1)?.split(" (")?.getOrNull(0)
            if (mountPoint != null && File(mountPoint).canonicalPath == canonical) return source
        }
        return null
    }

    private fun runCommand(command: List<String>, timeout: Duration? = null): CommandResult {
        val customRunner = commandRunner
        if (customRunner != null) {
            return customRunner(command, timeout)
        }
        logger.info("Running command: $command")
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        if (timeout != null) {
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                logger.error("Command timed out: $command")
                return CommandResult(1, "", "command timed out")
            }
        } else process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.exitValue()
        logger.info("Command $command exited with code $exitCode, stdout: $stdout, stderr: $stderr")
        return CommandResult(exitCode, stdout, stderr)
    }
}

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
