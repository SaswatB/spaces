import { createFileRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { trpc } from '../lib/trpc';

// @ts-expect-error - Route types are generated at build time
export const Route = createFileRoute('/entrypoints')({
  component: EntrypointsPage,
});

function EntrypointsPage() {
  const { data: entrypoints, isLoading } = trpc.entrypoints.list.useQuery();
  const [showCreate, setShowCreate] = useState(false);

  return (
    <>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>Entrypoints</h1>
        <button onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? 'Cancel' : 'Add Entrypoint'}
        </button>
      </header>

      {showCreate && <CreateEntrypointForm onClose={() => setShowCreate(false)} />}

      {isLoading ? (
        <div aria-busy="true">Loading...</div>
      ) : entrypoints?.length === 0 ? (
        <div className="empty-state">
          <p>No entrypoints yet</p>
          <p>
            <small>
              Entrypoints are the base directories that serve as foundations for
              layers.
            </small>
          </p>
        </div>
      ) : (
        <div>
          {entrypoints?.map((entrypoint) => (
            <EntrypointCard key={entrypoint.id} entrypoint={entrypoint} />
          ))}
        </div>
      )}
    </>
  );
}

function CreateEntrypointForm({ onClose }: { onClose: () => void }) {
  const utils = trpc.useUtils();
  const create = trpc.entrypoints.create.useMutation({
    onSuccess: () => {
      utils.entrypoints.list.invalidate();
      utils.system.status.invalidate();
      onClose();
    },
  });

  const [name, setName] = useState('');
  const [path, setPath] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    create.mutate({ name, path });
  };

  return (
    <article>
      <header>
        <strong>Add New Entrypoint</strong>
      </header>
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="My Project"
            required
          />
        </label>
        <label>
          Path
          <input
            type="text"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            placeholder="/path/to/base/directory"
            required
          />
          <small>Absolute path to the base directory</small>
        </label>
        {create.error && (
          <p style={{ color: 'var(--pico-del-color)' }}>
            {create.error.message}
          </p>
        )}
        <button type="submit" disabled={create.isPending} aria-busy={create.isPending}>
          Create Entrypoint
        </button>
      </form>
    </article>
  );
}

// Use any for the entrypoint type since dates are serialized as strings over JSON
function EntrypointCard({ entrypoint }: { entrypoint: { id: string; name: string; path: string } }) {
  const utils = trpc.useUtils();
  const deleteEntrypoint = trpc.entrypoints.delete.useMutation({
    onSuccess: () => {
      utils.entrypoints.list.invalidate();
      utils.system.status.invalidate();
    },
  });

  const [confirmDelete, setConfirmDelete] = useState(false);

  return (
    <article className="card">
      <div className="card-header">
        <h3 className="card-title">{entrypoint.name}</h3>
        <div className="button-group">
          <a
            href={`/layers?entrypointId=${entrypoint.id}`}
            role="button"
            className="outline button-small"
          >
            Layers
          </a>
          {confirmDelete ? (
            <>
              <button
                className="button-small"
                style={{ background: 'var(--pico-del-color)' }}
                onClick={() => deleteEntrypoint.mutate({ id: entrypoint.id })}
                disabled={deleteEntrypoint.isPending}
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
      <p className="path-display">{entrypoint.path}</p>
      {deleteEntrypoint.error && (
        <p style={{ color: 'var(--pico-del-color)', marginTop: '0.5rem' }}>
          {deleteEntrypoint.error.message}
        </p>
      )}
    </article>
  );
}
