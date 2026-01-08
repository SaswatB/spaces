#!/usr/bin/env node
import { api } from '../app/lib/api';

type Command = (args: string[]) => Promise<void>;

const commands: Record<string, Command> = {
  status: async () => {
    const status = await api.system.status();
    console.log(JSON.stringify(status, null, 2));
  },
  remount: async () => {
    await api.system.remount();
    console.log('OK');
  },
  'entrypoints:list': async () => {
    const entrypoints = await api.entrypoints.list();
    console.log(JSON.stringify(entrypoints, null, 2));
  },
  'entrypoints:create': async (args) => {
    const [name, path] = args;
    if (!name || !path) {
      throw new Error('Usage: entrypoints:create <name> <path>');
    }
    const entrypoint = await api.entrypoints.create({ name, path });
    console.log(JSON.stringify(entrypoint, null, 2));
  },
  'entrypoints:delete': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: entrypoints:delete <id>');
    }
    await api.entrypoints.delete(id);
    console.log('OK');
  },
  'layers:list': async (args) => {
    const [entrypointId] = args;
    const layers = await api.layers.list(entrypointId);
    console.log(JSON.stringify(layers, null, 2));
  },
  'layers:create': async (args) => {
    const [name, entrypointId, parentId] = args;
    if (!name || !entrypointId) {
      throw new Error('Usage: layers:create <name> <entrypointId> [parentId]');
    }
    const layer = await api.layers.create({
      name,
      entrypointId,
      parentId: parentId ?? null,
    });
    console.log(JSON.stringify(layer, null, 2));
  },
  'layers:delete': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: layers:delete <id>');
    }
    await api.layers.delete(id);
    console.log('OK');
  },
  'layers:mount': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: layers:mount <id>');
    }
    await api.layers.mount(id);
    console.log('OK');
  },
  'layers:unmount': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: layers:unmount <id>');
    }
    await api.layers.unmount(id);
    console.log('OK');
  },
  'mounts:list': async (args) => {
    const [entrypointId] = args;
    const mounts = await api.userMounts.list(entrypointId);
    console.log(JSON.stringify(mounts, null, 2));
  },
  'mounts:create': async (args) => {
    const [name, entrypointId, mountPath, attachedLayerId] = args;
    if (!name || !entrypointId || !mountPath) {
      throw new Error('Usage: mounts:create <name> <entrypointId> <mountPath> [attachedLayerId]');
    }
    const mount = await api.userMounts.create({
      name,
      entrypointId,
      mountPath,
      attachedLayerId: attachedLayerId ?? null,
    });
    console.log(JSON.stringify(mount, null, 2));
  },
  'mounts:delete': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: mounts:delete <id>');
    }
    await api.userMounts.delete(id);
    console.log('OK');
  },
  'mounts:mount': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: mounts:mount <id>');
    }
    await api.userMounts.mount(id);
    console.log('OK');
  },
  'mounts:unmount': async (args) => {
    const [id] = args;
    if (!id) {
      throw new Error('Usage: mounts:unmount <id>');
    }
    await api.userMounts.unmount(id);
    console.log('OK');
  },
  'mounts:attach': async (args) => {
    const [userMountId, layerId] = args;
    if (!userMountId) {
      throw new Error('Usage: mounts:attach <userMountId> [layerId]');
    }
    await api.userMounts.attachLayer({
      userMountId,
      layerId: layerId ?? null,
    });
    console.log('OK');
  },
};

async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (!command || command === 'help') {
    printHelp();
    return;
  }

  const handler = commands[command];
  if (!handler) {
    throw new Error(`Unknown command: ${command}`);
  }

  await handler(args);
}

function printHelp() {
  console.log(`Spaces CLI\n\nCommands:\n  status\n  remount\n  entrypoints:list\n  entrypoints:create <name> <path>\n  entrypoints:delete <id>\n  layers:list [entrypointId]\n  layers:create <name> <entrypointId> [parentId]\n  layers:delete <id>\n  layers:mount <id>\n  layers:unmount <id>\n  mounts:list [entrypointId]\n  mounts:create <name> <entrypointId> <mountPath> [attachedLayerId]\n  mounts:delete <id>\n  mounts:mount <id>\n  mounts:unmount <id>\n  mounts:attach <userMountId> [layerId]\n\nEnvironment:\n  SPACES_API_URL (default http://localhost:3100)\n  SPACES_AUTH_TOKEN (optional)\n`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
