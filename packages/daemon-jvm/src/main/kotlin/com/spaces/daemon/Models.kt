package com.spaces.daemon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Entrypoint(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class Layer(
    val id: String,
    val name: String,
    val entrypointId: String,
    val parentId: String?,
    val upperDir: String,
    val workDir: String,
    val mountPath: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class UserMount(
    val id: String,
    val name: String,
    val entrypointId: String,
    val attachedLayerId: String?,
    val mountPath: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StatusResponse(
    val entrypointCount: Int,
    val layerCount: Int,
    val userMountCount: Int,
    val mountedLayers: Int,
    val mountedUserMounts: Int
)

@Serializable
data class LayerResponse(
    val id: String,
    val name: String,
    val entrypointId: String,
    val parentId: String?,
    val upperDir: String,
    val workDir: String,
    val mountPath: String,
    val mountStatus: MountStatus,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class UserMountResponse(
    val id: String,
    val name: String,
    val entrypointId: String,
    val attachedLayerId: String?,
    val mountPath: String,
    val mountStatus: MountStatus,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CreateEntrypointRequest(
    val name: String? = null,
    val path: String
)

@Serializable
data class CreateLayerRequest(
    val name: String? = null,
    val entrypointId: String,
    val parentId: String? = null,
    val mountPath: String? = null
)

@Serializable
data class CreateUserMountRequest(
    val name: String,
    val entrypointId: String,
    val mountPath: String,
    val attachedLayerId: String? = null
)

@Serializable
data class AttachLayerRequest(
    val userMountId: String,
    val layerId: String? = null
)

@Serializable
data class LayerDiffEntry(
    val path: String,
    val changeType: LayerDiffType
)

@Serializable
enum class LayerDiffType {
    @SerialName("add")
    Add,
    @SerialName("modify")
    Modify,
    @SerialName("delete")
    Delete
}

@Serializable
enum class MountStatus {
    @SerialName("mounted")
    Mounted,
    @SerialName("unmounted")
    Unmounted,
    @SerialName("error")
    Error
}

@Serializable
data class ErrorResponse(val error: String)
