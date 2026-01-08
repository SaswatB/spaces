use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use utoipa::ToSchema;
use uuid::Uuid;

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct Entrypoint {
    pub id: Uuid,
    pub name: String,
    pub path: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct Layer {
    pub id: Uuid,
    pub name: String,
    pub entrypoint_id: Uuid,
    pub parent_id: Option<Uuid>,
    pub upper_dir: String,
    pub work_dir: String,
    pub mount_path: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UserMount {
    pub id: Uuid,
    pub name: String,
    pub entrypoint_id: Uuid,
    pub attached_layer_id: Option<Uuid>,
    pub upper_dir: String,
    pub work_dir: String,
    pub mount_path: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LayerWithStatus {
    pub layer: Layer,
    pub mount_status: MountStatus,
}

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UserMountWithStatus {
    pub user_mount: UserMount,
    pub mount_status: MountStatus,
    pub sync_state: Option<SyncState>,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum MountStatus {
    Mounted,
    Unmounted,
    Error,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum SyncStatus {
    Idle,
    Syncing,
    Error,
}

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct SyncState {
    pub user_mount_id: Uuid,
    pub status: SyncStatus,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub pending_changes: Vec<FileChange>,
    pub error: Option<String>,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum FileChangeType {
    Add,
    Modify,
    Delete,
}

#[derive(Clone, Debug, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct FileChange {
    pub change_type: FileChangeType,
    pub relative_path: String,
    pub timestamp: DateTime<Utc>,
    pub source_uid: u32,
    pub source_gid: u32,
}
