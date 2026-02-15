#!/usr/bin/env node
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { api } from "../src/lib/api";

type Entrypoint = Awaited<ReturnType<typeof api.entrypoints.list>>[number];
type Layer = Awaited<ReturnType<typeof api.layers.list>>[number];
type UserMount = Awaited<ReturnType<typeof api.userMounts.list>>[number];

type CommandHandler = (args: string[]) => Promise<void>;

type Category = "entrypoint" | "layer" | "mount" | "daemon";

type MatchBy = { id: string; name: string; path?: string; mountPath?: string };

const categoryAliases: Record<string, Category> = {
  entrypoint: "entrypoint",
  entrypoints: "entrypoint",
  ep: "entrypoint",
  e: "entrypoint",
  layer: "layer",
  layers: "layer",
  l: "layer",
  mount: "mount",
  mounts: "mount",
  m: "mount",
  daemon: "daemon",
  daemons: "daemon",
  d: "daemon",
};

function normalizePath(value: string): string {
  return path.resolve(value);
}

function isWithin(base: string, target: string): boolean {
  const basePath = normalizePath(base);
  const targetPath = normalizePath(target);
  return (
    targetPath === basePath || targetPath.startsWith(`${basePath}${path.sep}`)
  );
}

function selectByIdOrName<T extends MatchBy>(
  items: T[],
  ref: string
): T | null {
  const idMatch = items.find((item) => item.id === ref);
  if (idMatch) {
    return idMatch;
  }
  const nameMatches = items.filter((item) => item.name === ref);
  if (nameMatches.length > 1) {
    throw new Error(`Multiple matches for name: ${ref}`);
  }
  return nameMatches[0] ?? null;
}

function selectByPath<T extends MatchBy>(
  items: T[],
  targetPath: string,
  key: "path" | "mountPath"
) {
  const matches = items
    .filter((item) => item[key] && isWithin(item[key] as string, targetPath))
    .sort((a, b) => (b[key] as string).length - (a[key] as string).length);
  return matches[0] ?? null;
}

function stateDir(): string {
  if (process.env.SPACES_STATE_DIR) {
    return process.env.SPACES_STATE_DIR;
  }
  if (process.platform === "darwin") {
    return path.join(os.homedir(), "Library", "Application Support", "Spaces");
  }
  const base =
    process.env.XDG_STATE_HOME ?? path.join(os.homedir(), ".local", "state");
  return path.join(base, "spaces");
}

function ensureStateDir(): string {
  const dir = stateDir();
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function pidFilePath(): string {
  return path.join(stateDir(), "spacesd.pid");
}

function logFilePath(): string {
  return path.join(stateDir(), "spacesd.log");
}

function readPid(): number | null {
  const pidPath = pidFilePath();
  if (!fs.existsSync(pidPath)) {
    return null;
  }
  const raw = fs.readFileSync(pidPath, "utf8").trim();
  const pid = Number.parseInt(raw, 10);
  return Number.isFinite(pid) ? pid : null;
}

function isRunning(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function resolveDaemonPath(): string {
  if (process.env.SPACES_DAEMON_PATH) {
    return process.env.SPACES_DAEMON_PATH;
  }
  const execDir = path.dirname(process.execPath);
  const candidate = path.join(execDir, "spacesd");
  if (fs.existsSync(candidate)) {
    return candidate;
  }
  return "spacesd";
}

async function stopDaemon(): Promise<{ stopped: boolean; message: string }> {
  const pid = readPid();
  if (!pid) {
    return { stopped: false, message: "Daemon not running." };
  }
  if (!isRunning(pid)) {
    fs.rmSync(pidFilePath(), { force: true });
    return { stopped: true, message: `Removed stale pid ${pid}.` };
  }
  try {
    process.kill(pid, "SIGTERM");
  } catch (error) {
    return {
      stopped: false,
      message: `Failed to stop daemon: ${String(error)}`,
    };
  }

  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (!isRunning(pid)) {
      fs.rmSync(pidFilePath(), { force: true });
      return { stopped: true, message: `Stopped daemon ${pid}.` };
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }

  try {
    process.kill(pid, "SIGKILL");
  } catch (error) {
    return {
      stopped: false,
      message: `Failed to force stop daemon: ${String(error)}`,
    };
  }
  fs.rmSync(pidFilePath(), { force: true });
  return { stopped: true, message: `Force stopped daemon ${pid}.` };
}

async function resolveEntrypoint(ref?: string): Promise<Entrypoint | null> {
  const entrypoints = await api.entrypoints.list();
  if (ref) {
    const match = selectByIdOrName(entrypoints, ref);
    if (match) {
      return match;
    }
    return selectByPath(entrypoints, ref, "path");
  }
  return selectByPath(entrypoints, process.cwd(), "path");
}

async function resolveLayer(
  ref?: string,
  entrypoint?: Entrypoint | null
): Promise<Layer | null> {
  const layers = await api.layers.list(entrypoint?.id);
  if (ref) {
    const match = selectByIdOrName(layers, ref);
    if (match) {
      return match;
    }
    return selectByPath(layers, ref, "mountPath");
  }
  return selectByPath(layers, process.cwd(), "mountPath");
}

async function resolveUserMount(
  ref?: string,
  entrypoint?: Entrypoint | null
): Promise<UserMount | null> {
  const mounts = await api.userMounts.list(entrypoint?.id);
  if (ref) {
    const match = selectByIdOrName(mounts, ref);
    if (match) {
      return match;
    }
    return selectByPath(mounts, ref, "mountPath");
  }
  return selectByPath(mounts, process.cwd(), "mountPath");
}

const daemonCommands: Record<string, CommandHandler> = {
  status: async () => {
    const pid = readPid();
    if (pid && isRunning(pid)) {
      console.log(`Daemon running (pid ${pid}).`);
      return;
    }
    console.log("Daemon not running.");
  },
  start: async (args) => {
    const pid = readPid();
    if (pid && isRunning(pid)) {
      console.log(`Daemon already running (pid ${pid}).`);
      return;
    }
    if (pid && !isRunning(pid)) {
      fs.rmSync(pidFilePath(), { force: true });
    }
    ensureStateDir();
    const daemonPath = resolveDaemonPath();
    if (daemonPath.includes(path.sep) && !fs.existsSync(daemonPath)) {
      throw new Error(`Daemon binary not found at ${daemonPath}`);
    }

    const logPath = logFilePath();
    const logFd = fs.openSync(logPath, "a");
    let child: ReturnType<typeof spawn> | null = null;
    try {
      child = spawn(daemonPath, args, {
        detached: true,
        stdio: ["ignore", logFd, logFd],
        env: { ...process.env },
      });
      await new Promise<void>((resolve, reject) => {
        child?.once("error", reject);
        child?.once("spawn", resolve);
      });
    } catch (error) {
      if (child?.pid) {
        try {
          process.kill(child.pid, "SIGKILL");
        } catch {}
      }
      fs.closeSync(logFd);
      throw error;
    }
    child.unref();
    fs.writeFileSync(pidFilePath(), String(child.pid));
    fs.closeSync(logFd);
    console.log(`Started daemon (pid ${child.pid}).`);
    console.log(`Logs: ${logPath}`);
  },
  stop: async () => {
    const result = await stopDaemon();
    console.log(result.message);
  },
  restart: async (args) => {
    await stopDaemon();
    await daemonCommands.start?.(args);
  },
};

const rootCommands: Record<string, CommandHandler> = {
  status: async () => {
    const status = await api.system.status();
    console.log(JSON.stringify(status, null, 2));
  },
  remount: async () => {
    await api.system.remount();
    console.log("OK");
  },
};

const entrypointCommands: Record<string, CommandHandler> = {
  list: async (args) => {
    const [ref] = args;
    const entrypoint = await resolveEntrypoint(ref);
    if (ref && !entrypoint) {
      throw new Error(`Entrypoint not found: ${ref}`);
    }
    const entrypoints = entrypoint
      ? [entrypoint]
      : await api.entrypoints.list();
    console.log(JSON.stringify(entrypoints, null, 2));
  },
  create: async (args) => {
    const [pathArg, nameArg] = args;
    const pathValue = pathArg ? pathArg : process.cwd();
    const payload: { path: string; name?: string } = { path: pathValue };
    if (nameArg && nameArg.trim()) {
      payload.name = nameArg;
    }
    const entrypoint = await api.entrypoints.create(payload);
    console.log(JSON.stringify(entrypoint, null, 2));
  },
  delete: async (args) => {
    const [ref] = args;
    const entrypoint = await resolveEntrypoint(ref);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${ref ?? "(infer)"}`);
    }
    await api.entrypoints.delete(entrypoint.id);
    console.log("OK");
  },
};

const layerCommands: Record<string, CommandHandler> = {
  list: async (args) => {
    const [ref] = args;
    const entrypoint = await resolveEntrypoint(ref);
    if (ref && !entrypoint) {
      throw new Error(`Entrypoint not found: ${ref}`);
    }
    const layers = await api.layers.list(entrypoint?.id);
    console.log(JSON.stringify(layers, null, 2));
  },
  create: async (args) => {
    const [first, second, third] = args;
    const entrypoints = await api.entrypoints.list();
    let entrypoint: Entrypoint | null = null;
    let name: string | undefined;
    let parentRef: string | undefined;

    if (first) {
      const candidate =
        selectByIdOrName(entrypoints, first) ??
        selectByPath(entrypoints, first, "path");
      if (candidate) {
        entrypoint = candidate;
        name = second;
        parentRef = third;
      } else {
        entrypoint = selectByPath(entrypoints, process.cwd(), "path");
        name = first;
        parentRef = second;
      }
    } else {
      entrypoint = selectByPath(entrypoints, process.cwd(), "path");
    }

    if (!entrypoint) {
      throw new Error(
        "Unable to infer entrypoint for layer create. Provide an entrypoint ref."
      );
    }

    let parentId: string | null = null;
    if (parentRef) {
      const parent = await resolveLayer(parentRef, entrypoint);
      if (!parent) {
        throw new Error(`Parent layer not found: ${parentRef}`);
      }
      parentId = parent.id;
    }

    const payload: {
      entrypointId: string;
      name?: string;
      parentId?: string | null;
    } = {
      entrypointId: entrypoint.id,
    };
    if (name && name.trim()) {
      payload.name = name;
    }
    if (parentId) {
      payload.parentId = parentId;
    }

    const layer = await api.layers.create(payload);
    console.log(JSON.stringify(layer, null, 2));
  },
  delete: async (args) => {
    const [ref] = args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.delete(layer.id);
    console.log("OK");
  },
  mount: async (args) => {
    const [ref] = args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.mount(layer.id);
    console.log("OK");
  },
  unmount: async (args) => {
    const [ref] = args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.unmount(layer.id);
    console.log("OK");
  },
  diff: async (args) => {
    const [ref] = args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    const diff = await api.layers.diff(layer.id);
    console.log(JSON.stringify(diff, null, 2));
  },
};

const mountCommands: Record<string, CommandHandler> = {
  list: async (args) => {
    const [ref] = args;
    const entrypoint = await resolveEntrypoint(ref);
    if (ref && !entrypoint) {
      throw new Error(`Entrypoint not found: ${ref}`);
    }
    const mounts = await api.userMounts.list(entrypoint?.id);
    console.log(JSON.stringify(mounts, null, 2));
  },
  create: async (args) => {
    const [name, mountPath, entryRef, layerRef] = args;
    if (!name || !mountPath) {
      throw new Error(
        "Usage: mount create <name> <mountPath> [entrypoint] [layer]"
      );
    }
    const entrypoint = await resolveEntrypoint(entryRef);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${entryRef ?? "(infer)"}`);
    }
    let attachedLayerId: string | null = null;
    if (layerRef) {
      const layer = await resolveLayer(layerRef, entrypoint);
      if (!layer) {
        throw new Error(`Layer not found: ${layerRef}`);
      }
      attachedLayerId = layer.id;
    }
    const mount = await api.userMounts.create({
      name,
      entrypointId: entrypoint.id,
      mountPath,
      attachedLayerId,
    });
    console.log(JSON.stringify(mount, null, 2));
  },
  delete: async (args) => {
    const [ref] = args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.delete(mount.id);
    console.log("OK");
  },
  mount: async (args) => {
    const [ref] = args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.mount(mount.id);
    console.log("OK");
  },
  unmount: async (args) => {
    const [ref] = args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.unmount(mount.id);
    console.log("OK");
  },
  attach: async (args) => {
    const [mountRef, layerRef] = args;
    const mount = await resolveUserMount(mountRef, null);
    if (!mount) {
      throw new Error(`Mount not found: ${mountRef ?? "(infer)"}`);
    }
    let layerId: string | null = null;
    if (layerRef) {
      const entrypoint = await resolveEntrypoint(mount.entrypointId);
      const layer = await resolveLayer(layerRef, entrypoint ?? undefined);
      if (!layer) {
        throw new Error(`Layer not found: ${layerRef}`);
      }
      layerId = layer.id;
    }
    await api.userMounts.attachLayer({
      userMountId: mount.id,
      layerId,
    });
    console.log("OK");
  },
};

const categoryCommands: Record<Category, Record<string, CommandHandler>> = {
  entrypoint: entrypointCommands,
  layer: layerCommands,
  mount: mountCommands,
  daemon: daemonCommands,
};

async function main() {
  const [first, second, ...rest] = process.argv.slice(2);
  if (!first || first === "help") {
    printHelp();
    return;
  }

  const rootHandler = rootCommands[first];
  if (rootHandler) {
    await rootHandler([second, ...rest].filter((arg) => arg !== undefined));
    return;
  }

  const category = categoryAliases[first];
  if (!category) {
    throw new Error(`Unknown command: ${first}`);
  }

  if (!second || second === "help") {
    printCategoryHelp(category);
    return;
  }

  const handler = categoryCommands[category][second];
  if (!handler) {
    throw new Error(`Unknown subcommand: ${first} ${second}`);
  }

  await handler(rest);
}

function printHelp() {
  console.log(
    `Spaces CLI\n\nCommands:\n  status\n  remount\n  entrypoint|ep|e <subcommand>\n  layer|l <subcommand>\n  mount|m <subcommand>\n  daemon|d <subcommand>\n\nRun \"spaces <category> help\" to see subcommands.\n\nEnvironment:\n  SPACES_API_URL (default http://localhost:3100)\n  SPACES_AUTH_TOKEN (optional)\n  SPACES_DAEMON_PATH (optional)\n  SPACES_STATE_DIR (optional)\n`
  );
}

function printCategoryHelp(category: Category) {
  if (category === "entrypoint") {
    console.log(
      `Entrypoint commands:\n  entrypoint list [entrypoint|path]\n  entrypoint create [path] [name]\n  entrypoint delete [entrypoint|path]`
    );
    return;
  }
  if (category === "layer") {
    console.log(
      `Layer commands:\n  layer list [entrypoint|path]\n  layer create [entrypoint|path] [name] [parent]\n  layer delete [layer|path]\n  layer mount [layer|path]\n  layer unmount [layer|path]\n  layer diff [layer|path]`
    );
    return;
  }
  if (category === "daemon") {
    console.log(
      `Daemon commands:\n  daemon status\n  daemon start [args...]\n  daemon stop\n  daemon restart [args...]`
    );
    return;
  }
  console.log(
    `Mount commands:\n  mount list [entrypoint|path]\n  mount create <name> <mountPath> [entrypoint|path] [layer]\n  mount delete [mount|path]\n  mount mount [mount|path]\n  mount unmount [mount|path]\n  mount attach <mount|path> [layer]`
  );
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
