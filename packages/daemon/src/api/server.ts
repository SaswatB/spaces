import { createHTTPServer } from '@trpc/server/adapters/standalone';
import { appRouter, type Context } from './router.js';
import type { SpacesService } from '../services/spaces.js';
import type { Config } from '../types.js';

/**
 * Create and start the HTTP server for the tRPC API
 */
export function createServer(
  spacesService: SpacesService,
  port: number,
  config: Config
) {
  const server = createHTTPServer({
    router: appRouter,
    createContext: ({ req }): Context => ({
      spacesService,
      authToken:
        req?.headers['x-spaces-token'] ??
        req?.headers['authorization'] ??
        null,
      expectedAuthToken: config.authToken,
    }),
    // Enable CORS for web UI
    responseMeta() {
      return {
        headers: {
          'Access-Control-Allow-Origin': config.corsOrigin,
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Spaces-Token',
        },
      };
    },
  });

  server.listen(port, config.apiHost);

  console.log(`Spaces daemon listening on http://${config.apiHost}:${port}`);

  return server;
}
