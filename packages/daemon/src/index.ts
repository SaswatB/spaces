import { initializeDb } from './db/index.js';
import { SpacesService } from './services/spaces.js';
import { createServer } from './api/server.js';
import { isOverlayfsAvailable } from './overlay/index.js';
import type { Config } from './types.js';

// Default configuration
const config: Config = {
  dataDir: process.env['SPACES_DATA_DIR'] ?? '/var/lib/spaces',
  dbPath: process.env['SPACES_DB_PATH'] ?? '/var/lib/spaces/spaces.db',
  terminalCommand:
    process.env['SPACES_TERMINAL_CMD'] ??
    'x-terminal-emulator -e sh -c \'cd "$1" && exec "$SHELL"\' -- {path}',
  syncDebounceMs: parseInt(process.env['SPACES_SYNC_DEBOUNCE_MS'] ?? '100', 10),
  syncIgnorePatterns: (
    process.env['SPACES_SYNC_IGNORE'] ?? 'node_modules,.git,*.swp,*.swo,*~,.DS_Store'
  ).split(','),
  apiHost: process.env['SPACES_API_HOST'] ?? '127.0.0.1',
  corsOrigin: process.env['SPACES_CORS_ORIGIN'] ?? 'http://localhost:3000',
  authToken: process.env['SPACES_AUTH_TOKEN'] ?? null,
};

const PORT = parseInt(process.env['SPACES_PORT'] ?? '3100', 10);

async function main() {
  console.log('Starting Spaces daemon...');

  // Check if overlayfs is available
  if (!(await isOverlayfsAvailable())) {
    console.error(
      'Error: overlayfs is not available on this system. Make sure you are running on Linux with overlayfs support.'
    );
    process.exit(1);
  }

  // Check if running as root (required for mount operations)
  if (process.getuid?.() !== 0) {
    console.warn(
      'Warning: Not running as root. Mount operations may fail. Consider running with sudo.'
    );
  }

  // Initialize database
  console.log(`Initializing database at ${config.dbPath}...`);
  const { db } = initializeDb(config.dbPath);

  // Create services
  const spacesService = new SpacesService(db, config);

  // Remount all on startup
  console.log('Remounting all layers and user mounts...');
  await spacesService.remountAll();

  // Start HTTP server
  const server = createServer(spacesService, PORT, config);

  // Handle shutdown
  const shutdown = async () => {
    console.log('\nShutting down...');
    await spacesService.shutdown();
    server.close();
    process.exit(0);
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  console.log('Spaces daemon is ready!');
  console.log(`  Data directory: ${config.dataDir}`);
  console.log(`  Database: ${config.dbPath}`);
  console.log(`  API: http://${config.apiHost}:${PORT}`);
  if (config.authToken) {
    console.log('  Auth: token required');
  }
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
