import { createFileRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { trpc } from '../lib/trpc';

// @ts-expect-error - Route types are generated at build time
export const Route = createFileRoute('/mounts')({
  component: MountsPage,
});

function MountsPage() {
  const { data: mounts, isLoading } = trpc.userMounts.list.useQuery();
  const { data: entrypoints } = trpc.entrypoints.list.useQuery();
  const { data: layers } = trpc.layers.list.useQuery();
  const [showCreate, setShowCreate] = useState(false);

  return (
    <>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>User Mounts</h1>
        <button onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? 'Cancel' : 'Create User Mount'}
        </button>
      </header>

      {showCreate && (
        <CreateUserMountForm
          entrypoints={entrypoints ?? []}
          layers={layers ?? []}
          onClose={() => setShowCreate(false)}
        />
      )}

      {isLoading ? (
        <div aria-busy="true">Loading...</div>
      ) : mounts?.length === 0 ? (
        <div className="empty-state">
          <p>No user mounts yet</p>
          <p>
            <small>
              User mounts are well-known paths that can be attached to layers
              for easy development access.
            </small>
          </p>
        </div>
      ) : (
        <div>
          {mounts?.map((mount) => (
            <UserMountCard
              key={mount.id}
              mount={mount}
              layers={layers ?? []}
            />
          ))}
        </div>
      )}
    </>
  );
}

interface Entrypoint {
  id: string;
  name: string;
  path: string;
}

interface LayerWithStatus {
  id: string;
  name: string;
  entrypointId: string;
  parentId: string | null;
  mountPath: string;
  mountStatus: 'mounted' | 'unmounted' | 'error';
}

interface UserMountWithStatus {
  id: string;
  name: string;
  entrypointId: string;
  attachedLayerId: string | null;
  mountPath: string;
  mountStatus: 'mounted' | 'unmounted' | 'error';
  syncState?: {
    status: 'idle' | 'syncing' | 'error';
    lastSyncedAt: string | null; // JSON serializes dates as strings
    error: string | null;
  };
}

function CreateUserMountForm({
  entrypoints,
  layers,
  onClose,
}: {
  entrypoints: Entrypoint[];
  layers: LayerWithStatus[];
  onClose: () => void;
}) {
  const utils = trpc.useUtils();
  const create = trpc.userMounts.create.useMutation({
    onSuccess: () => {
      utils.userMounts.list.invalidate();
      utils.system.status.invalidate();
      onClose();
    },
  });

  const [name, setName] = useState('');
  const [entrypointId, setEntrypointId] = useState('');
  const [mountPath, setMountPath] = useState('');
  const [attachedLayerId, setAttachedLayerId] = useState<string | null>(null);

  const availableLayers = layers.filter((l) => l.entrypointId === entrypointId);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    create.mutate({
      name,
      entrypointId,
      mountPath,
      attachedLayerId,
    });
  };

  return (
    <article>
      <header>
        <strong>Create User Mount</strong>
      </header>
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="dev-workspace"
            required
          />
        </label>
        <label>
          Entrypoint
          <select
            value={entrypointId}
            onChange={(e) => {
              setEntrypointId(e.target.value);
              setAttachedLayerId(null);
            }}
            required
          >
            <option value="">Select an entrypoint...</option>
            {entrypoints.map((ep) => (
              <option key={ep.id} value={ep.id}>
                {ep.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Mount Path
          <input
            type="text"
            value={mountPath}
            onChange={(e) => setMountPath(e.target.value)}
            placeholder="/home/user/projects/myapp"
            required
          />
          <small>Where this mount will appear in your filesystem</small>
        </label>
        <label>
          Attached Layer (optional)
          <select
            value={attachedLayerId ?? ''}
            onChange={(e) => setAttachedLayerId(e.target.value || null)}
            disabled={!entrypointId}
          >
            <option value="">None (no sync)</option>
            {availableLayers.map((layer) => (
              <option key={layer.id} value={layer.id}>
                {layer.name}
              </option>
            ))}
          </select>
          <small>Changes will sync bidirectionally with this layer</small>
        </label>
        {create.error && (
          <p style={{ color: 'var(--pico-del-color)' }}>
            {create.error.message}
          </p>
        )}
        <button type="submit" disabled={create.isPending} aria-busy={create.isPending}>
          Create User Mount
        </button>
      </form>
    </article>
  );
}

function UserMountCard({
  mount,
  layers,
}: {
  mount: UserMountWithStatus;
  layers: LayerWithStatus[];
}) {
  const utils = trpc.useUtils();

  const mountUserMount = trpc.userMounts.mount.useMutation({
    onSuccess: () => utils.userMounts.list.invalidate(),
  });

  const unmountUserMount = trpc.userMounts.unmount.useMutation({
    onSuccess: () => utils.userMounts.list.invalidate(),
  });

  const openTerminal = trpc.userMounts.openTerminal.useMutation();

  const attachLayer = trpc.userMounts.attachLayer.useMutation({
    onSuccess: () => utils.userMounts.list.invalidate(),
  });

  const deleteMount = trpc.userMounts.delete.useMutation({
    onSuccess: () => {
      utils.userMounts.list.invalidate();
      utils.system.status.invalidate();
    },
  });

  const [confirmDelete, setConfirmDelete] = useState(false);
  const [showLayerSelect, setShowLayerSelect] = useState(false);

  const isMounted = mount.mountStatus === 'mounted';
  const attachedLayer = layers.find((l) => l.id === mount.attachedLayerId);
  const availableLayers = layers.filter((l) => l.entrypointId === mount.entrypointId);

  const getSyncStatusBadge = () => {
    if (!mount.syncState) return null;
    switch (mount.syncState.status) {
      case 'syncing':
        return <span className="badge badge-info">Syncing</span>;
      case 'error':
        return <span className="badge badge-error">Sync Error</span>;
      default:
        return <span className="badge badge-success">Synced</span>;
    }
  };

  return (
    <article className="card">
      <div className="card-header">
        <div>
          <span className={`status-dot ${isMounted ? 'mounted' : 'unmounted'}`} />
          <h3 className="card-title" style={{ display: 'inline' }}>
            {mount.name}
          </h3>
          {mount.attachedLayerId && getSyncStatusBadge()}
        </div>
        <div className="button-group">
          {isMounted ? (
            <>
              <button
                className="outline button-small"
                onClick={() => openTerminal.mutate({ id: mount.id })}
                disabled={openTerminal.isPending}
              >
                Terminal
              </button>
              <button
                className="outline button-small"
                onClick={() => unmountUserMount.mutate({ id: mount.id })}
                disabled={unmountUserMount.isPending}
              >
                Unmount
              </button>
            </>
          ) : (
            <button
              className="button-small"
              onClick={() => mountUserMount.mutate({ id: mount.id })}
              disabled={mountUserMount.isPending}
            >
              Mount
            </button>
          )}
          {confirmDelete ? (
            <>
              <button
                className="button-small"
                style={{ background: 'var(--pico-del-color)' }}
                onClick={() => deleteMount.mutate({ id: mount.id })}
                disabled={deleteMount.isPending}
              >
                Confirm
              </button>
              <button
                className="outline button-small"
                onClick={() => setConfirmDelete(false)}
              >
                Cancel
              </button>
            </>
          ) : (
            <button
              className="outline button-small"
              onClick={() => setConfirmDelete(true)}
            >
              Delete
            </button>
          )}
        </div>
      </div>

      <p className="path-display">{mount.mountPath}</p>

      <div style={{ marginTop: '1rem' }}>
        <strong>Attached Layer: </strong>
        {showLayerSelect ? (
          <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
            <select
              value={mount.attachedLayerId ?? ''}
              onChange={(e) => {
                attachLayer.mutate({
                  userMountId: mount.id,
                  layerId: e.target.value || null,
                });
                setShowLayerSelect(false);
              }}
              style={{ marginBottom: 0 }}
            >
              <option value="">None (no sync)</option>
              {availableLayers.map((layer) => (
                <option key={layer.id} value={layer.id}>
                  {layer.name}
                </option>
              ))}
            </select>
            <button
              className="outline button-small"
              onClick={() => setShowLayerSelect(false)}
            >
              Cancel
            </button>
          </div>
        ) : (
          <>
            {attachedLayer ? attachedLayer.name : 'None'}
            <button
              className="outline button-small"
              style={{ marginLeft: '0.5rem' }}
              onClick={() => setShowLayerSelect(true)}
            >
              Change
            </button>
          </>
        )}
      </div>

      {mount.syncState?.error && (
        <p style={{ color: 'var(--pico-del-color)', marginTop: '0.5rem' }}>
          Sync error: {mount.syncState.error}
        </p>
      )}

      {deleteMount.error && (
        <p style={{ color: 'var(--pico-del-color)', marginTop: '0.5rem' }}>
          {deleteMount.error.message}
        </p>
      )}
    </article>
  );
}
