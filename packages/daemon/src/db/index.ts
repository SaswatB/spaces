import Database, { type Database as DatabaseType } from 'better-sqlite3';
import { drizzle, type BetterSQLite3Database } from 'drizzle-orm/better-sqlite3';
import * as schema from './schema.js';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export * from './schema.js';

interface DbConnection {
  db: BetterSQLite3Database<typeof schema>;
  sqlite: DatabaseType;
}

export function createDb(dbPath: string): DbConnection {
  // Ensure the directory exists
  const dir = dirname(dbPath);
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }

  const sqlite = new Database(dbPath);

  // Enable WAL mode for better concurrent access
  sqlite.pragma('journal_mode = WAL');

  // Enable foreign keys
  sqlite.pragma('foreign_keys = ON');

  const db = drizzle(sqlite, { schema });

  return { db, sqlite };
}

export function initializeDb(dbPath: string): DbConnection {
  const { db, sqlite } = createDb(dbPath);

  // Create tables if they don't exist
  sqlite.exec(`
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
  `);

  return { db, sqlite };
}

export type Db = ReturnType<typeof createDb>['db'];
