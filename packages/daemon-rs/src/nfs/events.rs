use chrono::{DateTime, Utc};

#[derive(Clone, Debug)]
pub enum NfsOpKind {
    Create,
    Write,
    Rename,
    Remove,
    Mkdir,
    Rmdir,
    Setattr,
}

#[derive(Clone, Debug)]
pub struct NfsOp {
    pub mount_id: String,
    pub relative_path: String,
    pub kind: NfsOpKind,
    pub source_uid: u32,
    pub source_gid: u32,
    pub timestamp: DateTime<Utc>,
}

impl NfsOp {
}
