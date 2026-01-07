import { createTRPCReact, type CreateTRPCReact } from '@trpc/react-query';
import { httpBatchLink } from '@trpc/client';
import type { AppRouter } from '@spaces/daemon/api/router';

export const trpc: CreateTRPCReact<AppRouter, unknown> = createTRPCReact<AppRouter>();

const DAEMON_URL = typeof window !== 'undefined'
  ? (window as unknown as { __DAEMON_URL__?: string }).__DAEMON_URL__ ?? 'http://localhost:3100'
  : 'http://localhost:3100';

const DAEMON_TOKEN = typeof window !== 'undefined'
  ? (window as unknown as { __SPACES_AUTH_TOKEN__?: string }).__SPACES_AUTH_TOKEN__
  : (import.meta as unknown as { env?: { VITE_SPACES_AUTH_TOKEN?: string } }).env?.VITE_SPACES_AUTH_TOKEN;

export function createTRPCClient() {
  return trpc.createClient({
    links: [
      httpBatchLink({
        url: DAEMON_URL,
        headers: () =>
          DAEMON_TOKEN
            ? {
                'x-spaces-token': DAEMON_TOKEN,
              }
            : {},
      }),
    ],
  });
}
