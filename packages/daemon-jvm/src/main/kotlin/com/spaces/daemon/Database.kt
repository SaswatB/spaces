package com.spaces.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SpacesDatabase(dbPath: String) {
    private val lock = ReentrantLock()
    private val connection: Connection

    init {
        val path = Path.of(dbPath)
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    }

    fun initialize() = lock.withLock {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS entrypoints (
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  path TEXT NOT NULL UNIQUE,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                );
                """.trimIndent()
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS layers (
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  entrypoint_id TEXT NOT NULL REFERENCES entrypoints(id),
                  parent_id TEXT REFERENCES layers(id),
                  upper_dir TEXT NOT NULL UNIQUE,
                  work_dir TEXT NOT NULL UNIQUE,
                  mount_path TEXT NOT NULL UNIQUE,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                );
                """.trimIndent()
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS user_mounts (
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  entrypoint_id TEXT NOT NULL REFERENCES entrypoints(id),
                  attached_layer_id TEXT REFERENCES layers(id),
                  upper_dir TEXT NOT NULL UNIQUE,
                  work_dir TEXT NOT NULL UNIQUE,
                  mount_path TEXT NOT NULL UNIQUE,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                );
                """.trimIndent()
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_layers_entrypoint ON layers(entrypoint_id);")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_layers_parent ON layers(parent_id);")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_mounts_entrypoint ON user_mounts(entrypoint_id);")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_mounts_layer ON user_mounts(attached_layer_id);")
        }
    }

    fun listEntrypoints(): List<EntrypointRecord> = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, name, path, created_at, updated_at
            FROM entrypoints
            ORDER BY created_at
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<EntrypointRecord>()
                while (rs.next()) {
                    results.add(rs.toEntrypoint())
                }
                results
            }
        }
    }

    fun getEntrypoint(id: String): EntrypointRecord? = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, name, path, created_at, updated_at
            FROM entrypoints
            WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toEntrypoint() else null
            }
        }
    }

    fun getEntrypointByPath(path: String): EntrypointRecord? = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, name, path, created_at, updated_at
            FROM entrypoints
            WHERE path = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, path)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toEntrypoint() else null
            }
        }
    }

    fun insertEntrypoint(entrypoint: EntrypointRecord) = lock.withLock {
        connection.prepareStatement(
            """
            INSERT INTO entrypoints (id, name, path, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, entrypoint.id)
            stmt.setString(2, entrypoint.name)
            stmt.setString(3, entrypoint.path)
            stmt.setLong(4, entrypoint.createdAt)
            stmt.setLong(5, entrypoint.updatedAt)
            stmt.executeUpdate()
        }
    }

    fun deleteEntrypoint(id: String) = lock.withLock {
        connection.prepareStatement("DELETE FROM entrypoints WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    fun listLayers(entrypointId: String?): List<LayerRecord> = lock.withLock {
        val sql = if (entrypointId != null) {
            """
            SELECT id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM layers
            WHERE entrypoint_id = ?
            ORDER BY created_at
            """.trimIndent()
        } else {
            """
            SELECT id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM layers
            ORDER BY created_at
            """.trimIndent()
        }
        connection.prepareStatement(sql).use { stmt ->
            if (entrypointId != null) {
                stmt.setString(1, entrypointId)
            }
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<LayerRecord>()
                while (rs.next()) {
                    results.add(rs.toLayer())
                }
                results
            }
        }
    }

    fun getLayer(id: String): LayerRecord? = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM layers
            WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toLayer() else null
            }
        }
    }

    fun insertLayer(layer: LayerRecord) = lock.withLock {
        connection.prepareStatement(
            """
            INSERT INTO layers (id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, layer.id)
            stmt.setString(2, layer.name)
            stmt.setString(3, layer.entrypointId)
            stmt.setString(4, layer.parentId)
            stmt.setString(5, layer.upperDir)
            stmt.setString(6, layer.workDir)
            stmt.setString(7, layer.mountPath)
            stmt.setLong(8, layer.createdAt)
            stmt.setLong(9, layer.updatedAt)
            stmt.executeUpdate()
        }
    }

    fun deleteLayer(id: String) = lock.withLock {
        connection.prepareStatement("DELETE FROM layers WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    fun countChildLayers(id: String): Long = lock.withLock {
        connection.prepareStatement("SELECT COUNT(*) FROM layers WHERE parent_id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0
            }
        }
    }

    fun countAttachedUserMounts(layerId: String): Long = lock.withLock {
        connection.prepareStatement("SELECT COUNT(*) FROM user_mounts WHERE attached_layer_id = ?").use { stmt ->
            stmt.setString(1, layerId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0
            }
        }
    }

    fun listUserMounts(entrypointId: String?): List<UserMountRecord> = lock.withLock {
        val sql = if (entrypointId != null) {
            """
            SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM user_mounts
            WHERE entrypoint_id = ?
            ORDER BY created_at
            """.trimIndent()
        } else {
            """
            SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM user_mounts
            ORDER BY created_at
            """.trimIndent()
        }
        connection.prepareStatement(sql).use { stmt ->
            if (entrypointId != null) {
                stmt.setString(1, entrypointId)
            }
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<UserMountRecord>()
                while (rs.next()) {
                    results.add(rs.toUserMount())
                }
                results
            }
        }
    }

    fun listUserMountsByLayer(layerId: String): List<UserMountRecord> = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM user_mounts
            WHERE attached_layer_id = ?
            ORDER BY created_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, layerId)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<UserMountRecord>()
                while (rs.next()) {
                    results.add(rs.toUserMount())
                }
                results
            }
        }
    }

    fun getUserMount(id: String): UserMountRecord? = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
            FROM user_mounts
            WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toUserMount() else null
            }
        }
    }

    fun insertUserMount(userMount: UserMountRecord) = lock.withLock {
        connection.prepareStatement(
            """
            INSERT INTO user_mounts (id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, userMount.id)
            stmt.setString(2, userMount.name)
            stmt.setString(3, userMount.entrypointId)
            stmt.setString(4, userMount.attachedLayerId)
            stmt.setString(5, userMount.upperDir)
            stmt.setString(6, userMount.workDir)
            stmt.setString(7, userMount.mountPath)
            stmt.setLong(8, userMount.createdAt)
            stmt.setLong(9, userMount.updatedAt)
            stmt.executeUpdate()
        }
    }

    fun deleteUserMount(id: String) = lock.withLock {
        connection.prepareStatement("DELETE FROM user_mounts WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    fun updateUserMountLayer(id: String, layerId: String?, updatedAt: Long) = lock.withLock {
        connection.prepareStatement(
            """
            UPDATE user_mounts
            SET attached_layer_id = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, layerId)
            stmt.setLong(2, updatedAt)
            stmt.setString(3, id)
            stmt.executeUpdate()
        }
    }

    fun listLayerIds(): List<String> = lock.withLock {
        connection.prepareStatement("SELECT id FROM layers ORDER BY created_at").use { stmt ->
            stmt.executeQuery().use { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) {
                    ids.add(rs.getString("id"))
                }
                ids
            }
        }
    }

    fun listUserMountIds(): List<String> = lock.withLock {
        connection.prepareStatement("SELECT id FROM user_mounts ORDER BY created_at").use { stmt ->
            stmt.executeQuery().use { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) {
                    ids.add(rs.getString("id"))
                }
                ids
            }
        }
    }

    private fun ResultSet.toEntrypoint(): EntrypointRecord = EntrypointRecord(
        id = getString("id"),
        name = getString("name"),
        path = getString("path"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at")
    )

    private fun ResultSet.toLayer(): LayerRecord = LayerRecord(
        id = getString("id"),
        name = getString("name"),
        entrypointId = getString("entrypoint_id"),
        parentId = getString("parent_id"),
        upperDir = getString("upper_dir"),
        workDir = getString("work_dir"),
        mountPath = getString("mount_path"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at")
    )

    private fun ResultSet.toUserMount(): UserMountRecord = UserMountRecord(
        id = getString("id"),
        name = getString("name"),
        entrypointId = getString("entrypoint_id"),
        attachedLayerId = getString("attached_layer_id"),
        upperDir = getString("upper_dir"),
        workDir = getString("work_dir"),
        mountPath = getString("mount_path"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at")
    )
}

data class EntrypointRecord(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class LayerRecord(
    val id: String,
    val name: String,
    val entrypointId: String,
    val parentId: String?,
    val upperDir: String,
    val workDir: String,
    val mountPath: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class UserMountRecord(
    val id: String,
    val name: String,
    val entrypointId: String,
    val attachedLayerId: String?,
    val upperDir: String,
    val workDir: String,
    val mountPath: String,
    val createdAt: Long,
    val updatedAt: Long
)
