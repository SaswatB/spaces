package com.spaces.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

class SpacesService(
        private val config: Config,
        private val db: SpacesDatabase,
        private val mountManager: MountManager,
        private val replication: ReplicationService
) {
    private val overlay = OverlayEngine()

    fun listEntrypoints(): List<Entrypoint> = db.listEntrypoints().map { it.toEntrypoint() }

    fun getEntrypoint(id: String): Entrypoint? = db.getEntrypoint(id)?.toEntrypoint()

    fun createEntrypoint(name: String?, path: String): Entrypoint {
        val entryPath = Paths.get(path)
        if (!Files.exists(entryPath)) {
            throw IllegalArgumentException("Entrypoint path does not exist: $path")
        }
        val inferred = inferEntrypointName(entryPath)
        val finalName = name?.trim()?.takeIf { it.isNotEmpty() } ?: inferred
        val now = nowSeconds()
        val entrypoint =
                EntrypointRecord(
                        id = generateEntryPointId(),
                        name = finalName,
                        path = path,
                        createdAt = now,
                        updatedAt = now
                )
        db.insertEntrypoint(entrypoint)
        return entrypoint.toEntrypoint()
    }

    fun deleteEntrypoint(id: String) {
        val layers = db.listLayers(id)
        if (layers.isNotEmpty()) {
            throw IllegalArgumentException("Cannot delete entrypoint: layers depend on it")
        }
        val mounts = db.listUserMounts(id)
        if (mounts.isNotEmpty()) {
            throw IllegalArgumentException("Cannot delete entrypoint: user mounts depend on it")
        }
        db.deleteEntrypoint(id)
    }

    fun listLayers(entrypointId: String?): List<LayerResponse> =
            db.listLayers(entrypointId).map { layer ->
                layer.toLayerResponse(mountStatus(layer.mountPath))
            }

    fun getLayer(id: String): LayerResponse? =
            db.getLayer(id)?.let { layer -> layer.toLayerResponse(mountStatus(layer.mountPath)) }

    fun createLayer(
            name: String?,
            entrypointId: String,
            parentId: String?,
            mountPath: String?
    ): LayerResponse {
        val entrypoint =
                db.getEntrypoint(entrypointId)
                        ?: throw IllegalArgumentException("Entrypoint not found")
        val parent =
                parentId?.let { id ->
                    db.getLayer(id) ?: throw IllegalArgumentException("Parent layer not found")
                }
        if (parent != null && parent.entrypointId != entrypoint.id) {
            throw IllegalArgumentException("Parent layer must belong to the same entrypoint")
        }
        val now = nowSeconds()
        val id = generateLayerId()
        val layerName = generateLayerName(entrypoint, name)
        val upperDir = "${config.dataDir}/layers/$id/upper"
        val workDir = "${config.dataDir}/layers/$id/work"
        val defaultMountPath =
                "${config.dataDir}/mounts/layers/${entrypoint.id}/${sanitizeMountComponent(layerName)}"
        val resolvedMountPath = mountPath ?: defaultMountPath
        Files.createDirectories(Paths.get(upperDir))
        Files.createDirectories(Paths.get(workDir))
        Files.createDirectories(Paths.get(resolvedMountPath))
        val record =
                LayerRecord(
                        id = id,
                        name = layerName,
                        entrypointId = entrypointId,
                        parentId = parentId,
                        upperDir = upperDir,
                        workDir = workDir,
                        mountPath = resolvedMountPath,
                        createdAt = now,
                        updatedAt = now
                )
        db.insertLayer(record)
        mountLayer(record)
        return record.toLayerResponse(mountStatus(resolvedMountPath))
    }

    fun deleteLayer(id: String) {
        if (db.countChildLayers(id) > 0) {
            throw IllegalArgumentException("Cannot delete layer: child layers depend on it")
        }
        if (db.countAttachedUserMounts(id) > 0) {
            throw IllegalArgumentException("Cannot delete layer: user mounts are attached")
        }
        val layer = db.getLayer(id)
        if (layer != null) {
            runCatching { unmountLayer(layer) }
        }
        db.deleteLayer(id)
    }

    fun mountLayer(layer: LayerRecord) {
        val exportPath = layerExportPath(layer.id)
        mountManager.ensureMount(exportPath, layer.mountPath)
    }

    fun unmountLayer(layer: LayerRecord) {
        mountManager.unmount(layer.mountPath)
    }

    fun listUserMounts(entrypointId: String?): List<UserMountResponse> =
            db.listUserMounts(entrypointId).map { mount ->
                mount.toUserMountResponse(mountStatus(mount.mountPath))
            }

    fun getUserMount(id: String): UserMountResponse? =
            db.getUserMount(id)?.let { mount ->
                mount.toUserMountResponse(mountStatus(mount.mountPath))
            }

    fun createUserMount(
            name: String,
            entrypointId: String,
            mountPath: String,
            attachedLayerId: String?
    ): UserMountResponse {
        val entrypoint =
                db.getEntrypoint(entrypointId)
                        ?: throw IllegalArgumentException("Entrypoint not found")
        if (attachedLayerId != null) {
            val layer =
                    db.getLayer(attachedLayerId)
                            ?: throw IllegalArgumentException("Layer not found")
            if (layer.entrypointId != entrypoint.id) {
                throw IllegalArgumentException("Layer must belong to the same entrypoint")
            }
        }
        val now = nowSeconds()
        val id = generateUserMountId()
        val upperDir = "${config.dataDir}/usermounts/$id/upper"
        val workDir = "${config.dataDir}/usermounts/$id/work"
        Files.createDirectories(Paths.get(upperDir))
        Files.createDirectories(Paths.get(workDir))
        Files.createDirectories(Paths.get(mountPath))
        val record =
                UserMountRecord(
                        id = id,
                        name = name,
                        entrypointId = entrypointId,
                        attachedLayerId = attachedLayerId,
                        upperDir = upperDir,
                        workDir = workDir,
                        mountPath = mountPath,
                        createdAt = now,
                        updatedAt = now
                )
        db.insertUserMount(record)
        mountUserMount(record)
        if (attachedLayerId != null) {
            val layer = db.getLayer(attachedLayerId)
            if (layer != null) {
                mountLayer(layer)
            }
        }
        return record.toUserMountResponse(mountStatus(mountPath))
    }

    fun deleteUserMount(id: String) {
        val mount = db.getUserMount(id)
        if (mount != null) {
            runCatching { unmountUserMount(mount) }
        }
        db.deleteUserMount(id)
    }

    fun attachLayer(userMountId: String, layerId: String?) {
        val mount =
                db.getUserMount(userMountId)
                        ?: throw IllegalArgumentException("User mount not found")
        val oldLayerId = mount.attachedLayerId
        if (layerId != null) {
            val layer = db.getLayer(layerId) ?: throw IllegalArgumentException("Layer not found")
            if (layer.entrypointId != mount.entrypointId) {
                throw IllegalArgumentException("Layer must belong to the same entrypoint")
            }
        }
        val now = nowSeconds()
        db.updateUserMountLayer(userMountId, layerId, now)
        val updated = db.getUserMount(userMountId)
        if (layerId != null) {
            val layer = db.getLayer(layerId)
            if (layer != null) {
                mountLayer(layer)
            }
        }
        if (updated != null && oldLayerId != layerId) {
            replication.handleLayerSwitchInvalidation(updated.mountPath, oldLayerId, layerId)
        }
    }

    fun mountUserMount(mount: UserMountRecord) {
        val exportPath = userMountExportPath(mount.id)
        mountManager.ensureMount(exportPath, mount.mountPath)
    }

    fun unmountUserMount(mount: UserMountRecord) {
        mountManager.unmount(mount.mountPath)
    }

    fun remountAll() {
        val layers = sortLayersByDependency(db.listLayers(null))
        for (layer in layers) {
            runCatching { mountLayer(layer) }
        }
        val mounts = db.listUserMounts(null)
        for (mount in mounts) {
            runCatching { mountUserMount(mount) }
        }
    }

    fun unmountAll() {
        val mounts = db.listUserMounts(null)
        for (mount in mounts) {
            runCatching { unmountUserMount(mount) }
        }
        val layers = db.listLayers(null)
        for (layer in layers) {
            runCatching { unmountLayer(layer) }
        }
    }

    fun status(): StatusResponse {
        val entrypoints = db.listEntrypoints()
        val layers = db.listLayers(null)
        val mounts = db.listUserMounts(null)
        val mountedLayers = layers.count { mountStatus(it.mountPath) == MountStatus.Mounted }
        val mountedMounts = mounts.count { mountStatus(it.mountPath) == MountStatus.Mounted }
        return StatusResponse(
                entrypointCount = entrypoints.size,
                layerCount = layers.size,
                userMountCount = mounts.size,
                mountedLayers = mountedLayers,
                mountedUserMounts = mountedMounts
        )
    }

    fun layerDiff(id: String): List<LayerDiffEntry> {
        val layer = db.getLayer(id) ?: throw IllegalArgumentException("Layer not found")
        val entrypoint =
                db.getEntrypoint(layer.entrypointId)
                        ?: throw IllegalArgumentException("Entrypoint not found")
        val upperRoot = Paths.get(layer.upperDir)
        val lowerView = buildLowerViewForLayer(layer, entrypoint)
        val entries = mutableListOf<LayerDiffEntry>()
        if (Files.exists(upperRoot)) {
            collectLayerDiff(upperRoot, upperRoot, lowerView, entries)
        }
        return entries.sortedBy { it.path }
    }

    private fun mountStatus(mountPath: String): MountStatus {
        return if (mountManager.isMounted(mountPath)) MountStatus.Mounted else MountStatus.Unmounted
    }

    private fun layerExportPath(id: String): String = exportPath("layers/$id")
    private fun userMountExportPath(id: String): String = exportPath("mounts/$id")
    private fun exportPath(suffix: String): String {
        return if (!suffix.startsWith("/")) "/$suffix" else suffix
    }

    private fun inferEntrypointName(path: Path): String {
        val name = path.fileName?.toString()
        if (name.isNullOrBlank()) {
            throw IllegalArgumentException("Unable to infer entrypoint name from path: $path")
        }
        return name
    }

    private fun generateLayerName(entrypoint: EntrypointRecord, name: String?): String {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) return trimmed

        val existing = db.listLayers(entrypoint.id).map { it.name }.toHashSet()
        var index = existing.size + 1
        while (true) {
            val candidate = "${entrypoint.name}_$index"
            if (!existing.contains(candidate)) {
                return candidate
            }
            index += 1
        }
    }

    private fun sanitizeMountComponent(name: String): String =
            name.replace('/', '_').replace('\\', '_')

    private fun generateEntryPointId(): String =
            generatePrefixedId("ep") { id -> db.getEntrypoint(id) != null }
    private fun generateLayerId(): String =
            generatePrefixedId("lyr") { id -> db.getLayer(id) != null }
    private fun generateUserMountId(): String =
            generatePrefixedId("mnt") { id -> db.getUserMount(id) != null }

    private fun generatePrefixedId(prefix: String, exists: (String) -> Boolean): String {
        val modulo = 10_000_000_000L
        while (true) {
            val raw = Math.floorMod(UUID.randomUUID().mostSignificantBits, modulo)
            val id = "${prefix}_${raw.toString().padStart(10, '0')}"
            if (!exists(id)) {
                return id
            }
        }
    }

    private fun nowSeconds(): Long = Instant.now().epochSecond

    private fun EntrypointRecord.toEntrypoint(): Entrypoint =
            Entrypoint(
                    id = id,
                    name = name,
                    path = path,
                    createdAt = Instant.ofEpochSecond(createdAt).toString(),
                    updatedAt = Instant.ofEpochSecond(updatedAt).toString()
            )

    private fun LayerRecord.toLayerResponse(status: MountStatus): LayerResponse =
            LayerResponse(
                    id = id,
                    name = name,
                    entrypointId = entrypointId,
                    parentId = parentId,
                    upperDir = upperDir,
                    workDir = workDir,
                    mountPath = mountPath,
                    mountStatus = status,
                    createdAt = Instant.ofEpochSecond(createdAt).toString(),
                    updatedAt = Instant.ofEpochSecond(updatedAt).toString()
            )

    private fun UserMountRecord.toUserMountResponse(status: MountStatus): UserMountResponse =
            UserMountResponse(
                    id = id,
                    name = name,
                    entrypointId = entrypointId,
                    attachedLayerId = attachedLayerId,
                    upperDir = upperDir,
                    workDir = workDir,
                    mountPath = mountPath,
                    mountStatus = status,
                    createdAt = Instant.ofEpochSecond(createdAt).toString(),
                    updatedAt = Instant.ofEpochSecond(updatedAt).toString()
            )

    private fun sortLayersByDependency(layers: List<LayerRecord>): List<LayerRecord> {
        val remaining = layers.map { it.id }.toMutableSet()
        val layerMap = layers.associateBy { it.id }
        val result = mutableListOf<LayerRecord>()
        while (remaining.isNotEmpty()) {
            var progressed = false
            val ids = remaining.toList()
            for (id in ids) {
                val layer = layerMap[id] ?: continue
                val parent = layer.parentId
                if (parent == null || !remaining.contains(parent)) {
                    result.add(layer)
                    remaining.remove(id)
                    progressed = true
                }
            }
            if (!progressed) {
                break
            }
        }
        return result
    }

    private fun collectLayerDiff(
            upperRoot: Path,
            current: Path,
            lowerView: OverlayView,
            entries: MutableList<LayerDiffEntry>
    ) {
        Files.newDirectoryStream(current).use { stream ->
            for (entry in stream) {
                val rel = upperRoot.relativize(entry)
                val name = entry.fileName.toString()
                if (name == OPAQUE_MARKER) {
                    continue
                }
                val whiteout = isWhiteoutMarker(name)
                if (whiteout != null) {
                    val deletePath = rel.parent?.resolve(whiteout) ?: Paths.get(whiteout)
                    entries.add(LayerDiffEntry(deletePath.toString(), LayerDiffType.Delete))
                    continue
                }
                val changeType = if (overlay.exists(lowerView, rel.toString())) LayerDiffType.Modify else LayerDiffType.Add
                entries.add(LayerDiffEntry(rel.toString(), changeType))
                if (Files.isDirectory(entry)) {
                    collectLayerDiff(upperRoot, entry, lowerView, entries)
                }
            }
        }
    }

    private fun buildLowerViewForLayer(layer: LayerRecord, entrypoint: EntrypointRecord): OverlayView {
        val lowerLayers = mutableListOf<Path>()
        var current = layer.parentId?.let { db.getLayer(it) }
        while (current != null) {
            lowerLayers.add(Paths.get(current.upperDir))
            current = current.parentId?.let { db.getLayer(it) }
        }
        return OverlayView(Paths.get(entrypoint.path), lowerLayers)
    }
}
