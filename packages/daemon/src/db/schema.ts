import { sqliteTable, text, integer } from 'drizzle-orm/sqlite-core';
import { relations } from 'drizzle-orm';

// ============================================================================
// Tables
// ============================================================================

export const entrypoints = sqliteTable('entrypoints', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  path: text('path').notNull().unique(),
  createdAt: integer('created_at', { mode: 'timestamp' }).notNull(),
  updatedAt: integer('updated_at', { mode: 'timestamp' }).notNull(),
});

export const layers = sqliteTable('layers', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  entrypointId: text('entrypoint_id').notNull().references(() => entrypoints.id),
  parentId: text('parent_id'), // Self-reference handled in SQL schema directly
  upperDir: text('upper_dir').notNull().unique(),
  workDir: text('work_dir').notNull().unique(),
  mountPath: text('mount_path').notNull().unique(),
  createdAt: integer('created_at', { mode: 'timestamp' }).notNull(),
  updatedAt: integer('updated_at', { mode: 'timestamp' }).notNull(),
});

export const userMounts = sqliteTable('user_mounts', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  entrypointId: text('entrypoint_id').notNull().references(() => entrypoints.id),
  attachedLayerId: text('attached_layer_id').references(() => layers.id),
  upperDir: text('upper_dir').notNull().unique(),
  workDir: text('work_dir').notNull().unique(),
  mountPath: text('mount_path').notNull().unique(),
  createdAt: integer('created_at', { mode: 'timestamp' }).notNull(),
  updatedAt: integer('updated_at', { mode: 'timestamp' }).notNull(),
});

// ============================================================================
// Relations
// ============================================================================

export const entrypointsRelations = relations(entrypoints, ({ many }) => ({
  layers: many(layers),
  userMounts: many(userMounts),
}));

export const layersRelations = relations(layers, ({ one, many }) => ({
  entrypoint: one(entrypoints, {
    fields: [layers.entrypointId],
    references: [entrypoints.id],
  }),
  parent: one(layers, {
    fields: [layers.parentId],
    references: [layers.id],
    relationName: 'layerHierarchy',
  }),
  children: many(layers, { relationName: 'layerHierarchy' }),
  userMounts: many(userMounts),
}));

export const userMountsRelations = relations(userMounts, ({ one }) => ({
  entrypoint: one(entrypoints, {
    fields: [userMounts.entrypointId],
    references: [entrypoints.id],
  }),
  attachedLayer: one(layers, {
    fields: [userMounts.attachedLayerId],
    references: [layers.id],
  }),
}));
