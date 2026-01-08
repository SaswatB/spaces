import {
  Outlet,
  ScrollRestoration,
  createRootRoute,
  HeadContent,
} from '@tanstack/react-router';
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      { title: 'Spaces - OverlayFS Manager' },
    ],
    links: [
      {
        rel: 'stylesheet',
        href: 'https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css',
      },
    ],
  }),
  component: RootComponent,
});

function RootComponent() {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <QueryClientProvider client={queryClient}>
      <RootDocument>
        <Outlet />
      </RootDocument>
    </QueryClientProvider>
  );
}

function RootDocument({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <HeadContent />
        <style
          dangerouslySetInnerHTML={{
            __html: `
              :root {
                --pico-font-size: 16px;
              }
              .container {
                max-width: 1200px;
              }
              nav {
                margin-bottom: 2rem;
              }
              .card {
                background: var(--pico-card-background-color);
                border-radius: var(--pico-border-radius);
                padding: 1.5rem;
                margin-bottom: 1rem;
              }
              .card-header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 1rem;
              }
              .card-title {
                margin: 0;
                font-size: 1.25rem;
              }
              .badge {
                display: inline-block;
                padding: 0.25rem 0.5rem;
                border-radius: 0.25rem;
                font-size: 0.75rem;
                font-weight: 600;
                text-transform: uppercase;
              }
              .badge-success {
                background: #22c55e;
                color: white;
              }
              .badge-warning {
                background: #f59e0b;
                color: white;
              }
              .badge-error {
                background: #ef4444;
                color: white;
              }
              .badge-info {
                background: #3b82f6;
                color: white;
              }
              .button-group {
                display: flex;
                gap: 0.5rem;
              }
              .button-small {
                padding: 0.25rem 0.5rem;
                font-size: 0.875rem;
              }
              .tree {
                margin-left: 1.5rem;
              }
              .tree-item {
                position: relative;
                padding-left: 1rem;
              }
              .tree-item::before {
                content: '';
                position: absolute;
                left: 0;
                top: 0.75rem;
                width: 0.5rem;
                border-top: 1px solid var(--pico-muted-border-color);
              }
              .status-dot {
                display: inline-block;
                width: 0.5rem;
                height: 0.5rem;
                border-radius: 50%;
                margin-right: 0.5rem;
              }
              .status-dot.mounted {
                background: #22c55e;
              }
              .status-dot.unmounted {
                background: #ef4444;
              }
              .status-dot.syncing {
                background: #3b82f6;
                animation: pulse 1s infinite;
              }
              @keyframes pulse {
                0%, 100% { opacity: 1; }
                50% { opacity: 0.5; }
              }
              .empty-state {
                text-align: center;
                padding: 3rem;
                color: var(--pico-muted-color);
              }
              .grid-2 {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
                gap: 1rem;
              }
              .path-display {
                font-family: monospace;
                font-size: 0.875rem;
                color: var(--pico-muted-color);
                word-break: break-all;
              }
            `,
          }}
        />
      </head>
      <body>
        <main className="container">
          <nav>
            <ul>
              <li>
                <strong>Spaces</strong>
              </li>
            </ul>
            <ul>
              <li>
                <a href="/">Dashboard</a>
              </li>
              <li>
                <a href="/entrypoints">Entrypoints</a>
              </li>
              <li>
                <a href="/layers">Layers</a>
              </li>
              <li>
                <a href="/mounts">User Mounts</a>
              </li>
            </ul>
          </nav>
          {children}
        </main>
        <ScrollRestoration />
      </body>
    </html>
  );
}
