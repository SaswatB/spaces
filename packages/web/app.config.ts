import { defineConfig } from '@tanstack/start/config';
import tsConfigPaths from 'vite-tsconfig-paths';
import { TanStackRouterVite } from '@tanstack/router-plugin/vite';

export default defineConfig({
  vite: {
    plugins: [
      tsConfigPaths(),
      TanStackRouterVite({
        routesDirectory: './app/routes',
        generatedRouteTree: './app/routeTree.gen.ts',
      }),
    ],
  },
}) as object;
