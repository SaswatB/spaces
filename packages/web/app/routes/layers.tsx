import { createFileRoute, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, Entrypoint, Layer } from "../lib/api";

// @ts-expect-error - Route types are generated at build time
export const Route = createFileRoute("/layers")({
  component: LayersPage,
  validateSearch: (search: Record<string, unknown>) => ({
    entrypointId: search.entrypointId as string | undefined,
  }),
});

function LayersPage() {
  // @ts-expect-error - Route types are generated at build time
  const { entrypointId } = useSearch({ from: "/layers" });
  const { data: layers, isLoading } = useQuery({
    queryKey: ["layers", "list", entrypointId],
    queryFn: () => api.layers.list(entrypointId),
  });
  const { data: entrypoints } = useQuery({
    queryKey: ["entrypoints", "list"],
    queryFn: () => api.entrypoints.list(),
  });
  const [showCreate, setShowCreate] = useState(false);

  const selectedEntrypoint = entrypoints?.find((e) => e.id === entrypointId);

  return (
    <>
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <div>
          <h1>Layers</h1>
          {selectedEntrypoint && (
            <p style={{ margin: 0 }}>
              Filtered by: <strong>{selectedEntrypoint.name}</strong>{" "}
              <a href="/layers">(clear)</a>
            </p>
          )}
        </div>
        <button onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? "Cancel" : "Create Layer"}
        </button>
      </header>

      {showCreate && (
        <CreateLayerForm
          entrypoints={entrypoints ?? []}
          layers={layers ?? []}
          defaultEntrypointId={entrypointId}
          onClose={() => setShowCreate(false)}
        />
      )}

      {isLoading ? (
        <div aria-busy="true">Loading...</div>
      ) : layers?.length === 0 ? (
        <div className="empty-state">
          <p>No layers yet</p>
          <p>
            <small>
              Layers represent sets of changes on top of an entrypoint or
              another layer.
            </small>
          </p>
        </div>
      ) : (
        <LayerTree layers={layers ?? []} entrypoints={entrypoints ?? []} />
      )}
    </>
  );
}

function CreateLayerForm({
  entrypoints,
  layers,
  defaultEntrypointId,
  onClose,
}: {
  entrypoints: Entrypoint[];
  layers: Layer[];
  defaultEntrypointId?: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const create = useMutation({
    mutationFn: (payload: {
      name: string;
      entrypointId: string;
      parentId: string | null;
    }) => api.layers.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["layers", "list"] });
      queryClient.invalidateQueries({ queryKey: ["system", "status"] });
      onClose();
    },
  });

  const [name, setName] = useState("");
  const [entrypointId, setEntrypointId] = useState(defaultEntrypointId ?? "");
  const [parentId, setParentId] = useState<string | null>(null);

  const availableLayers = layers.filter((l) => l.entrypointId === entrypointId);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    create.mutate({
      name,
      entrypointId,
      parentId,
    });
  };

  return (
    <article>
      <header>
        <strong>Create New Layer</strong>
      </header>
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="feature-branch"
            required
          />
        </label>
        <label>
          Entrypoint
          <select
            value={entrypointId}
            onChange={(e) => {
              setEntrypointId(e.target.value);
              setParentId(null);
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
          Parent Layer (optional)
          <select
            value={parentId ?? ""}
            onChange={(e) => setParentId(e.target.value || null)}
            disabled={!entrypointId}
          >
            <option value="">None (directly on entrypoint)</option>
            {availableLayers.map((layer) => (
              <option key={layer.id} value={layer.id}>
                {layer.name}
              </option>
            ))}
          </select>
          <small>Stack this layer on top of another layer</small>
        </label>
        {create.error && (
          <p style={{ color: "var(--pico-del-color)" }}>
            {create.error.message}
          </p>
        )}
        <button
          type="submit"
          disabled={create.isPending}
          aria-busy={create.isPending}
        >
          Create Layer
        </button>
      </form>
    </article>
  );
}

function LayerTree({
  layers,
  entrypoints,
}: {
  layers: Layer[];
  entrypoints: Entrypoint[];
}) {
  // Group layers by entrypoint
  const layersByEntrypoint = new Map<string, Layer[]>();
  for (const layer of layers) {
    const existing = layersByEntrypoint.get(layer.entrypointId) ?? [];
    existing.push(layer);
    layersByEntrypoint.set(layer.entrypointId, existing);
  }

  return (
    <div>
      {Array.from(layersByEntrypoint.entries()).map(
        ([entrypointId, entrypointLayers]) => {
          const entrypoint = entrypoints.find((e) => e.id === entrypointId);
          return (
            <article key={entrypointId} className="card">
              <div className="card-header">
                <h3 className="card-title">{entrypoint?.name ?? "Unknown"}</h3>
                <span className="badge badge-info">
                  {entrypointLayers.length} layers
                </span>
              </div>
              <LayerTreeLevel layers={entrypointLayers} parentId={null} />
            </article>
          );
        }
      )}
    </div>
  );
}

function LayerTreeLevel({
  layers,
  parentId,
}: {
  layers: Layer[];
  parentId: string | null;
}) {
  const childLayers = layers.filter((l) => l.parentId === parentId);

  if (childLayers.length === 0) return null;

  return (
    <div className={parentId ? "tree" : ""}>
      {childLayers.map((layer) => (
        <div key={layer.id} className="tree-item">
          <LayerCard layer={layer} />
          <LayerTreeLevel layers={layers} parentId={layer.id} />
        </div>
      ))}
    </div>
  );
}

function LayerCard({ layer }: { layer: Layer }) {
  const queryClient = useQueryClient();
  const mountLayer = useMutation({
    mutationFn: (payload: { id: string }) => api.layers.mount(payload.id),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["layers", "list"] }),
  });

  const unmountLayer = useMutation({
    mutationFn: (payload: { id: string }) => api.layers.unmount(payload.id),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["layers", "list"] }),
  });

  const deleteLayer = useMutation({
    mutationFn: (payload: { id: string }) => api.layers.delete(payload.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["layers", "list"] });
      queryClient.invalidateQueries({ queryKey: ["system", "status"] });
    },
  });

  const [confirmDelete, setConfirmDelete] = useState(false);

  const isMounted = layer.mountStatus === "mounted";

  return (
    <div
      style={{
        padding: "0.75rem",
        background: "var(--pico-card-sectioning-background-color)",
        borderRadius: "var(--pico-border-radius)",
        marginBottom: "0.5rem",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <div>
          <span
            className={`status-dot ${isMounted ? "mounted" : "unmounted"}`}
          />
          <strong>{layer.name}</strong>
        </div>
        <div className="button-group">
          {isMounted ? (
            <>
              <button
                className="outline button-small"
                onClick={() => unmountLayer.mutate({ id: layer.id })}
                disabled={unmountLayer.isPending}
              >
                Unmount
              </button>
            </>
          ) : (
            <button
              className="button-small"
              onClick={() => mountLayer.mutate({ id: layer.id })}
              disabled={mountLayer.isPending}
            >
              Mount
            </button>
          )}
          {confirmDelete ? (
            <>
              <button
                className="button-small"
                style={{ background: "var(--pico-del-color)" }}
                onClick={() => deleteLayer.mutate({ id: layer.id })}
                disabled={deleteLayer.isPending}
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
      <p
        className="path-display"
        style={{ marginTop: "0.5rem", marginBottom: 0 }}
      >
        {layer.mountPath}
      </p>
      {deleteLayer.error && (
        <p
          style={{
            color: "var(--pico-del-color)",
            marginTop: "0.5rem",
            marginBottom: 0,
          }}
        >
          {deleteLayer.error.message}
        </p>
      )}
    </div>
  );
}
