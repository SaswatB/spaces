import { createFileRoute } from '@tanstack/react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';

export const Route = createFileRoute('/')({
  component: Dashboard,
});

function Dashboard() {
  const { data: status, isLoading, error } = useQuery({
    queryKey: ['system', 'status'],
    queryFn: () => api.system.status(),
  });

  if (isLoading) {
    return <div aria-busy="true">Loading...</div>;
  }

  if (error) {
    return (
      <article>
        <header>Error</header>
        <p>Failed to connect to daemon: {error.message}</p>
        <p>Make sure the daemon is running on port 3100.</p>
      </article>
    );
  }

  return (
    <>
      <h1>Dashboard</h1>

      <div className="grid-2">
        <article>
          <header>
            <strong>Entrypoints</strong>
          </header>
          <p style={{ fontSize: '2rem', fontWeight: 'bold' }}>
            {status?.entrypointCount ?? 0}
          </p>
          <footer>
            <a href="/entrypoints" role="button" className="outline">
              Manage Entrypoints
            </a>
          </footer>
        </article>

        <article>
          <header>
            <strong>Layers</strong>
          </header>
          <p style={{ fontSize: '2rem', fontWeight: 'bold' }}>
            {status?.mountedLayers ?? 0} / {status?.layerCount ?? 0}
          </p>
          <small>mounted</small>
          <footer>
            <a href="/layers" role="button" className="outline">
              Manage Layers
            </a>
          </footer>
        </article>

        <article>
          <header>
            <strong>User Mounts</strong>
          </header>
          <p style={{ fontSize: '2rem', fontWeight: 'bold' }}>
            {status?.mountedUserMounts ?? 0} / {status?.userMountCount ?? 0}
          </p>
          <small>mounted</small>
          <footer>
            <a href="/mounts" role="button" className="outline">
              Manage User Mounts
            </a>
          </footer>
        </article>
      </div>

      <article>
        <header>
          <strong>Quick Actions</strong>
        </header>
        <div className="button-group">
          <RemountAllButton />
        </div>
      </article>
    </>
  );
}

function RemountAllButton() {
  const queryClient = useQueryClient();
  const remountAll = useMutation({
    mutationFn: () => api.system.remount(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system', 'status'] });
      queryClient.invalidateQueries({ queryKey: ['layers', 'list'] });
      queryClient.invalidateQueries({ queryKey: ['user-mounts', 'list'] });
    },
  });

  return (
    <button
      onClick={() => remountAll.mutate()}
      disabled={remountAll.isPending}
      aria-busy={remountAll.isPending}
    >
      Remount All
    </button>
  );
}
