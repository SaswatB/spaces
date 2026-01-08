import type { components } from './openapi-types';

const DAEMON_URL = typeof window !== 'undefined'
  ? (window as unknown as { __DAEMON_URL__?: string }).__DAEMON_URL__ ?? 'http://localhost:3100'
  : process.env.SPACES_API_URL ?? 'http://localhost:3100';

const DAEMON_TOKEN = typeof window !== 'undefined'
  ? (window as unknown as { __SPACES_AUTH_TOKEN__?: string }).__SPACES_AUTH_TOKEN__
  : process.env.SPACES_AUTH_TOKEN ??
    (import.meta as unknown as { env?: { VITE_SPACES_AUTH_TOKEN?: string } }).env?.VITE_SPACES_AUTH_TOKEN;

type Entrypoint = components['schemas']['Entrypoint'];
type LayerResponse = components['schemas']['LayerResponse'];
type UserMountResponse = components['schemas']['UserMountResponse'];
type StatusResponse = components['schemas']['StatusResponse'];

type RequestOptions = {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | undefined>;
};

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const url = new URL(path, DAEMON_URL);
  if (options.query) {
    for (const [key, value] of Object.entries(options.query)) {
      if (value !== undefined) {
        url.searchParams.set(key, value);
      }
    }
  }

  const headers: Record<string, string> = {};
  if (DAEMON_TOKEN) {
    headers['x-spaces-token'] = DAEMON_TOKEN;
  }
  if (options.body !== undefined) {
    headers['content-type'] = 'application/json';
  }

  const response = await fetch(url.toString(), {
    method: options.method ?? 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (response.status === 204) {
    return undefined as T;
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with ${response.status}`);
  }

  return (await response.json()) as T;
}

export const api = {
  system: {
    status: () => request<StatusResponse>('/system/status'),
    remount: () => request<void>('/system/remount', { method: 'POST' }),
  },
  entrypoints: {
    list: () => request<Entrypoint[]>('/entrypoints'),
    create: (payload: { name: string; path: string }) =>
      request<Entrypoint>('/entrypoints', { method: 'POST', body: payload }),
    delete: (id: string) => request<void>(`/entrypoints/${id}`, { method: 'DELETE' }),
  },
  layers: {
    list: (entrypointId?: string) =>
      request<LayerResponse[]>('/layers', { query: { entrypointId } }),
    create: (payload: {
      name: string;
      entrypointId: string;
      parentId: string | null;
      mountPath?: string;
    }) => request<LayerResponse>('/layers', { method: 'POST', body: payload }),
    delete: (id: string) => request<void>(`/layers/${id}`, { method: 'DELETE' }),
    mount: (id: string) => request<void>(`/layers/${id}/mount`, { method: 'POST' }),
    unmount: (id: string) => request<void>(`/layers/${id}/unmount`, { method: 'POST' }),
  },
  userMounts: {
    list: (entrypointId?: string) =>
      request<UserMountResponse[]>('/user-mounts', { query: { entrypointId } }),
    create: (payload: {
      name: string;
      entrypointId: string;
      mountPath: string;
      attachedLayerId: string | null;
    }) => request<UserMountResponse>('/user-mounts', { method: 'POST', body: payload }),
    delete: (id: string) => request<void>(`/user-mounts/${id}`, { method: 'DELETE' }),
    mount: (id: string) => request<void>(`/user-mounts/${id}/mount`, { method: 'POST' }),
    unmount: (id: string) => request<void>(`/user-mounts/${id}/unmount`, { method: 'POST' }),
    attachLayer: (payload: { userMountId: string; layerId: string | null }) =>
      request<void>('/user-mounts/attach', { method: 'POST', body: payload }),
  },
};
