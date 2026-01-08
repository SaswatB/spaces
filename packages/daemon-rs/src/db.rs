use anyhow::Result;
use rusqlite::{types::Type, Connection, OptionalExtension};
use std::path::Path;
use std::sync::{Arc, Mutex};
use uuid::Uuid;

use crate::models::{Entrypoint, Layer, UserMount};

#[derive(Clone)]
pub struct Database {
    connection: Arc<Mutex<Connection>>,
}

impl Database {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self> {
        let path = path.as_ref();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let connection = Connection::open(path)?;
        Ok(Self {
            connection: Arc::new(Mutex::new(connection)),
        })
    }

    pub fn initialize(&self) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS entrypoints (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              path TEXT NOT NULL UNIQUE,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            );
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
            CREATE INDEX IF NOT EXISTS idx_layers_entrypoint ON layers(entrypoint_id);
            CREATE INDEX IF NOT EXISTS idx_layers_parent ON layers(parent_id);
            CREATE INDEX IF NOT EXISTS idx_user_mounts_entrypoint ON user_mounts(entrypoint_id);
            CREATE INDEX IF NOT EXISTS idx_user_mounts_layer ON user_mounts(attached_layer_id);
            ",
        )?;
        Ok(())
    }

    pub fn list_entrypoints(&self) -> Result<Vec<Entrypoint>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare(
            "SELECT id, name, path, created_at, updated_at FROM entrypoints ORDER BY created_at",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Entrypoint {
                id: parse_uuid(row.get::<_, String>(0)?)?,
                name: row.get(1)?,
                path: row.get(2)?,
                created_at: row.get(3)?,
                updated_at: row.get(4)?,
            })
        })?;
        let mut entrypoints = Vec::new();
        for row in rows {
            entrypoints.push(row?);
        }
        Ok(entrypoints)
    }

    pub fn get_entrypoint(&self, id: Uuid) -> Result<Option<Entrypoint>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare(
            "SELECT id, name, path, created_at, updated_at FROM entrypoints WHERE id = ?1",
        )?;
        let entrypoint = stmt
            .query_row([id.to_string()], |row| {
                Ok(Entrypoint {
                    id: parse_uuid(row.get::<_, String>(0)?)?,
                    name: row.get(1)?,
                    path: row.get(2)?,
                    created_at: row.get(3)?,
                    updated_at: row.get(4)?,
                })
            })
            .optional()?;
        Ok(entrypoint)
    }

    pub fn get_entrypoint_by_path(&self, path: &str) -> Result<Option<Entrypoint>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare(
            "SELECT id, name, path, created_at, updated_at FROM entrypoints WHERE path = ?1",
        )?;
        let entrypoint = stmt
            .query_row([path], |row| {
                Ok(Entrypoint {
                    id: parse_uuid(row.get::<_, String>(0)?)?,
                    name: row.get(1)?,
                    path: row.get(2)?,
                    created_at: row.get(3)?,
                    updated_at: row.get(4)?,
                })
            })
            .optional()?;
        Ok(entrypoint)
    }

    pub fn insert_entrypoint(&self, entrypoint: &Entrypoint) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute(
            "INSERT INTO entrypoints (id, name, path, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            (
                entrypoint.id.to_string(),
                entrypoint.name.as_str(),
                entrypoint.path.as_str(),
                entrypoint.created_at,
                entrypoint.updated_at,
            ),
        )?;
        Ok(())
    }

    pub fn delete_entrypoint(&self, id: Uuid) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute("DELETE FROM entrypoints WHERE id = ?1", [id.to_string()])?;
        Ok(())
    }

    pub fn list_layers(&self, entrypoint_id: Option<Uuid>) -> Result<Vec<Layer>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let (query, params): (&str, Vec<String>) = if let Some(entrypoint_id) = entrypoint_id {
            (
                "SELECT id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at
                 FROM layers WHERE entrypoint_id = ?1 ORDER BY created_at",
                vec![entrypoint_id.to_string()],
            )
        } else {
            (
                "SELECT id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at
                 FROM layers ORDER BY created_at",
                vec![],
            )
        };

        let mut stmt = conn.prepare(query)?;
        let rows = stmt.query_map(rusqlite::params_from_iter(params.iter()), |row| {
            Ok(Layer {
                id: parse_uuid(row.get::<_, String>(0)?)?,
                name: row.get(1)?,
                entrypoint_id: parse_uuid(row.get::<_, String>(2)?)?,
                parent_id: parse_optional_uuid(row.get::<_, Option<String>>(3)?)?,
                upper_dir: row.get(4)?,
                work_dir: row.get(5)?,
                mount_path: row.get(6)?,
                created_at: row.get(7)?,
                updated_at: row.get(8)?,
            })
        })?;
        let mut layers = Vec::new();
        for row in rows {
            layers.push(row?);
        }
        Ok(layers)
    }

    pub fn get_layer(&self, id: Uuid) -> Result<Option<Layer>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare(
            "SELECT id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at
             FROM layers WHERE id = ?1",
        )?;
        let layer = stmt
            .query_row([id.to_string()], |row| {
                Ok(Layer {
                    id: parse_uuid(row.get::<_, String>(0)?)?,
                    name: row.get(1)?,
                    entrypoint_id: parse_uuid(row.get::<_, String>(2)?)?,
                    parent_id: parse_optional_uuid(row.get::<_, Option<String>>(3)?)?,
                    upper_dir: row.get(4)?,
                    work_dir: row.get(5)?,
                    mount_path: row.get(6)?,
                    created_at: row.get(7)?,
                    updated_at: row.get(8)?,
                })
            })
            .optional()?;
        Ok(layer)
    }

    pub fn insert_layer(&self, layer: &Layer) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute(
            "INSERT INTO layers (id, name, entrypoint_id, parent_id, upper_dir, work_dir, mount_path, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            (
                layer.id.to_string(),
                layer.name.as_str(),
                layer.entrypoint_id.to_string(),
                layer.parent_id.map(|id| id.to_string()),
                layer.upper_dir.as_str(),
                layer.work_dir.as_str(),
                layer.mount_path.as_str(),
                layer.created_at,
                layer.updated_at,
            ),
        )?;
        Ok(())
    }

    pub fn delete_layer(&self, id: Uuid) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute("DELETE FROM layers WHERE id = ?1", [id.to_string()])?;
        Ok(())
    }

    pub fn count_child_layers(&self, id: Uuid) -> Result<i64> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare("SELECT COUNT(*) FROM layers WHERE parent_id = ?1")?;
        let count: i64 = stmt.query_row([id.to_string()], |row| row.get(0))?;
        Ok(count)
    }

    pub fn count_attached_user_mounts(&self, layer_id: Uuid) -> Result<i64> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt =
            conn.prepare("SELECT COUNT(*) FROM user_mounts WHERE attached_layer_id = ?1")?;
        let count: i64 = stmt.query_row([layer_id.to_string()], |row| row.get(0))?;
        Ok(count)
    }

    pub fn list_user_mounts(&self, entrypoint_id: Option<Uuid>) -> Result<Vec<UserMount>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let (query, params): (&str, Vec<String>) = if let Some(entrypoint_id) = entrypoint_id {
            (
                "SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
                 FROM user_mounts WHERE entrypoint_id = ?1 ORDER BY created_at",
                vec![entrypoint_id.to_string()],
            )
        } else {
            (
                "SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
                 FROM user_mounts ORDER BY created_at",
                vec![],
            )
        };

        let mut stmt = conn.prepare(query)?;
        let rows = stmt.query_map(rusqlite::params_from_iter(params.iter()), |row| {
            Ok(UserMount {
                id: parse_uuid(row.get::<_, String>(0)?)?,
                name: row.get(1)?,
                entrypoint_id: parse_uuid(row.get::<_, String>(2)?)?,
                attached_layer_id: parse_optional_uuid(row.get::<_, Option<String>>(3)?)?,
                upper_dir: row.get(4)?,
                work_dir: row.get(5)?,
                mount_path: row.get(6)?,
                created_at: row.get(7)?,
                updated_at: row.get(8)?,
            })
        })?;
        let mut mounts = Vec::new();
        for row in rows {
            mounts.push(row?);
        }
        Ok(mounts)
    }

    pub fn list_user_mounts_by_layer(&self, layer_id: Uuid) -> Result<Vec<UserMount>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare(
            "SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
             FROM user_mounts WHERE attached_layer_id = ?1 ORDER BY created_at",
        )?;
        let rows = stmt.query_map([layer_id.to_string()], |row| {
            Ok(UserMount {
                id: parse_uuid(row.get::<_, String>(0)?)?,
                name: row.get(1)?,
                entrypoint_id: parse_uuid(row.get::<_, String>(2)?)?,
                attached_layer_id: parse_optional_uuid(row.get::<_, Option<String>>(3)?)?,
                upper_dir: row.get(4)?,
                work_dir: row.get(5)?,
                mount_path: row.get(6)?,
                created_at: row.get(7)?,
                updated_at: row.get(8)?,
            })
        })?;
        let mut mounts = Vec::new();
        for row in rows {
            mounts.push(row?);
        }
        Ok(mounts)
    }

    pub fn get_user_mount(&self, id: Uuid) -> Result<Option<UserMount>> {
        let conn = self.connection.lock().expect("db lock poisoned");
        let mut stmt = conn.prepare(
            "SELECT id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at
             FROM user_mounts WHERE id = ?1",
        )?;
        let user_mount = stmt
            .query_row([id.to_string()], |row| {
                Ok(UserMount {
                    id: parse_uuid(row.get::<_, String>(0)?)?,
                    name: row.get(1)?,
                    entrypoint_id: parse_uuid(row.get::<_, String>(2)?)?,
                    attached_layer_id: parse_optional_uuid(row.get::<_, Option<String>>(3)?)?,
                    upper_dir: row.get(4)?,
                    work_dir: row.get(5)?,
                    mount_path: row.get(6)?,
                    created_at: row.get(7)?,
                    updated_at: row.get(8)?,
                })
            })
            .optional()?;
        Ok(user_mount)
    }

    pub fn insert_user_mount(&self, user_mount: &UserMount) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute(
            "INSERT INTO user_mounts (id, name, entrypoint_id, attached_layer_id, upper_dir, work_dir, mount_path, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            (
                user_mount.id.to_string(),
                user_mount.name.as_str(),
                user_mount.entrypoint_id.to_string(),
                user_mount.attached_layer_id.map(|id| id.to_string()),
                user_mount.upper_dir.as_str(),
                user_mount.work_dir.as_str(),
                user_mount.mount_path.as_str(),
                user_mount.created_at,
                user_mount.updated_at,
            ),
        )?;
        Ok(())
    }

    pub fn delete_user_mount(&self, id: Uuid) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute("DELETE FROM user_mounts WHERE id = ?1", [id.to_string()])?;
        Ok(())
    }

    pub fn update_user_mount_layer(&self, id: Uuid, layer_id: Option<Uuid>) -> Result<()> {
        let conn = self.connection.lock().expect("db lock poisoned");
        conn.execute(
            "UPDATE user_mounts SET attached_layer_id = ?1, updated_at = strftime('%s','now') WHERE id = ?2",
            (
                layer_id.map(|layer| layer.to_string()),
                id.to_string(),
            ),
        )?;
        Ok(())
    }
}

fn parse_uuid(value: String) -> rusqlite::Result<Uuid> {
    Uuid::parse_str(&value).map_err(|err| {
        rusqlite::Error::FromSqlConversionFailure(value.len(), Type::Text, Box::new(err))
    })
}

fn parse_optional_uuid(value: Option<String>) -> rusqlite::Result<Option<Uuid>> {
    match value {
        Some(value) => Ok(Some(parse_uuid(value)?)),
        None => Ok(None),
    }
}
