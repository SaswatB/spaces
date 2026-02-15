#!/usr/bin/env node
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { api } from "../src/lib/api";

type Entrypoint = Awaited<ReturnType<typeof api.entrypoints.list>>[number];
type Layer = Awaited<ReturnType<typeof api.layers.list>>[number];
type UserMount = Awaited<ReturnType<typeof api.userMounts.list>>[number];
type SystemStatus = Awaited<ReturnType<typeof api.system.status>>;

type Category = "entrypoint" | "layer" | "mount" | "daemon";

type GlobalOptions = {
  json: boolean;
  force: boolean;
};

type CommandContext = {
  args: string[];
  opts: GlobalOptions;
};

type CommandHandler = (ctx: CommandContext) => Promise<void>;

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

const DAEMON_URL = process.env.SPACES_API_URL ?? "http://localhost:3100";

function normalizePath(value: string): string {
  return path.resolve(value);
}

function isWithin(base: string, target: string): boolean {
  const basePath = normalizePath(base);
  const targetPath = normalizePath(target);
  return targetPath === basePath || targetPath.startsWith(`${basePath}${path.sep}`);
}

function selectByIdOrName<T extends MatchBy>(items: T[], ref: string): T | null {
  const idMatch = items.find((item) => item.id === ref);
  if (idMatch) return idMatch;

  const nameMatches = items.filter((item) => item.name === ref);
  if (nameMatches.length > 1) {
    throw new Error(`Multiple matches for name: ${ref}. Use an id to disambiguate.`);
  }
  return nameMatches[0] ?? null;
}

function selectByPath<T extends MatchBy>(items: T[], targetPath: string, key: "path" | "mountPath"): T | null {
  const matches = items
    .filter((item) => item[key] && isWithin(item[key] as string, targetPath))
    .sort((a, b) => (b[key] as string).length - (a[key] as string).length);
  return matches[0] ?? null;
}

function parseGlobalOptions(argv: string[]): { opts: GlobalOptions; args: string[] } {
  const opts: GlobalOptions = { json: false, force: false };
  const args: string[] = [];

  for (const arg of argv) {
    if (arg === "--json" || arg === "-j") {
      opts.json = true;
      continue;
    }
    if (arg === "--force" || arg === "-f") {
      opts.force = true;
      continue;
    }
    args.push(arg);
  }

  return { opts, args };
}

function parseFlags(args: string[]): { flags: Record<string, string | boolean>; positional: string[] } {
  const flags: Record<string, string | boolean> = {};
  const positional: string[] = [];

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (!arg) {
      continue;
    }
    if (!arg.startsWith("--")) {
      positional.push(arg);
      continue;
    }

    const eqIndex = arg.indexOf("=");
    if (eqIndex !== -1) {
      const key = arg.slice(2, eqIndex);
      flags[key] = arg.slice(eqIndex + 1);
      continue;
    }

    const key = arg.slice(2);
    const next = args[i + 1];
    if (next && !next.startsWith("--")) {
      flags[key] = next;
      i += 1;
    } else {
      flags[key] = true;
    }
  }

  return { flags, positional };
}

function getFlagString(flags: Record<string, string | boolean>, names: string[]): string | undefined {
  for (const name of names) {
    const value = flags[name];
    if (typeof value === "string" && value.trim()) {
      return value;
    }
  }
  return undefined;
}

function hasUnknownFlags(flags: Record<string, string | boolean>, allowed: string[]): string[] {
  const allowedSet = new Set(allowed);
  return Object.keys(flags).filter((flag) => !allowedSet.has(flag));
}

function stateDir(): string {
  if (process.env.SPACES_STATE_DIR) {
    return process.env.SPACES_STATE_DIR;
  }
  if (process.platform === "darwin") {
    return path.join(os.homedir(), "Library", "Application Support", "Spaces");
  }
  const base = process.env.XDG_STATE_HOME ?? path.join(os.homedir(), ".local", "state");
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

function printTable(rows: Array<Record<string, string>>): void {
  if (rows.length === 0) {
    console.log("No results.");
    return;
  }

  const first = rows[0];
  if (!first) {
    console.log("No results.");
    return;
  }
  const headers = Object.keys(first);
  const widths = headers.map((header) => {
    const maxCell = rows.reduce((max, row) => Math.max(max, (row[header] ?? "").length), header.length);
    return maxCell;
  });

  const fmt = (cells: string[]) => cells.map((cell, i) => cell.padEnd(widths[i] ?? 0)).join("  ");
  console.log(fmt(headers));
  console.log(fmt(widths.map((w) => "-".repeat(w))));
  for (const row of rows) {
    console.log(fmt(headers.map((h) => row[h] ?? "")));
  }
}

function outputData<T>(ctx: CommandContext, data: T, human: (value: T) => void): void {
  if (ctx.opts.json) {
    console.log(JSON.stringify(data, null, 2));
    return;
  }
  human(data);
}

async function confirmOrThrow(prompt: string, force: boolean): Promise<void> {
  if (force) return;

  if (!process.stdin.isTTY) {
    throw new Error(`Refusing destructive action in non-interactive mode. Re-run with --force.`);
  }

  const rl = createInterface({ input, output });
  try {
    const answer = (await rl.question(`${prompt} [y/N] `)).trim().toLowerCase();
    if (answer !== "y" && answer !== "yes") {
      throw new Error("Cancelled.");
    }
  } finally {
    rl.close();
  }
}

function printStatus(status: SystemStatus): void {
  console.log(`Entrypoints: ${status.entrypointCount}`);
  console.log(`Layers: ${status.layerCount} (${status.mountedLayers} mounted)`);
  console.log(`User mounts: ${status.userMountCount} (${status.mountedUserMounts} mounted)`);
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
    if (match) return match;
    return selectByPath(entrypoints, ref, "path");
  }
  return selectByPath(entrypoints, process.cwd(), "path");
}

async function resolveLayer(ref?: string, entrypoint?: Entrypoint | null): Promise<Layer | null> {
  const layers = await api.layers.list(entrypoint?.id);
  if (ref) {
    const match = selectByIdOrName(layers, ref);
    if (match) return match;
    return selectByPath(layers, ref, "mountPath");
  }
  return selectByPath(layers, process.cwd(), "mountPath");
}

async function resolveUserMount(ref?: string, entrypoint?: Entrypoint | null): Promise<UserMount | null> {
  const mounts = await api.userMounts.list(entrypoint?.id);
  if (ref) {
    const match = selectByIdOrName(mounts, ref);
    if (match) return match;
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
  start: async ({ args }) => {
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
        } catch {
          // ignore cleanup failures
        }
      }
      fs.closeSync(logFd);
      throw error;
    }

    child.unref();
    const startedPid = child.pid;
    if (!startedPid) {
      throw new Error("Daemon process started without a pid.");
    }
    fs.writeFileSync(pidFilePath(), String(startedPid));
    fs.closeSync(logFd);
    console.log(`Started daemon (pid ${startedPid}).`);
    console.log(`Logs: ${logPath}`);
  },
  stop: async () => {
    const result = await stopDaemon();
    console.log(result.message);
  },
  restart: async ({ args }) => {
    await stopDaemon();
    const startHandler = daemonCommands.start;
    if (!startHandler) {
      throw new Error("Daemon start command unavailable.");
    }
    await startHandler({ args, opts: { json: false, force: false } });
  },
};

const rootCommands: Record<string, CommandHandler> = {
  status: async (ctx) => {
    const status = await api.system.status();
    outputData(ctx, status, printStatus);
  },
  remount: async () => {
    await api.system.remount();
    console.log("Remounted all layer and user mounts.");
  },
};

const entrypointCommands: Record<string, CommandHandler> = {
  list: async (ctx) => {
    const [ref] = ctx.args;
    const entrypoint = await resolveEntrypoint(ref);
    if (ref && !entrypoint) {
      throw new Error(`Entrypoint not found: ${ref}`);
    }
    const entrypoints = entrypoint ? [entrypoint] : await api.entrypoints.list();
    outputData(ctx, entrypoints, (rows) => {
      printTable(rows.map((it) => ({ id: it.id, name: it.name, path: it.path })));
    });
  },
  create: async (ctx) => {
    const { flags, positional } = parseFlags(ctx.args);
    const unknown = hasUnknownFlags(flags, ["path", "name"]);
    if (unknown.length > 0) {
      throw new Error(`Unknown option(s): ${unknown.map((u) => `--${u}`).join(", ")}`);
    }

    const pathValue = getFlagString(flags, ["path"]) ?? positional[0] ?? process.cwd();
    const nameValue = getFlagString(flags, ["name"]) ?? positional[1];

    const payload: { path: string; name?: string } = { path: pathValue };
    if (nameValue && nameValue.trim()) payload.name = nameValue;

    const entrypoint = await api.entrypoints.create(payload);
    outputData(ctx, entrypoint, (value) => {
      console.log(`Created entrypoint ${value.name} (${value.id})`);
      console.log(`Path: ${value.path}`);
    });
  },
  delete: async (ctx) => {
    const [ref] = ctx.args;
    const entrypoint = await resolveEntrypoint(ref);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${ref ?? "(infer)"}`);
    }

    await confirmOrThrow(
      `Delete entrypoint '${entrypoint.name}' (${entrypoint.id})?`,
      ctx.opts.force,
    );

    await api.entrypoints.delete(entrypoint.id);
    console.log(`Deleted entrypoint ${entrypoint.id}.`);
  },
};

const layerCommands: Record<string, CommandHandler> = {
  list: async (ctx) => {
    const [ref] = ctx.args;
    const entrypoint = await resolveEntrypoint(ref);
    if (ref && !entrypoint) {
      throw new Error(`Entrypoint not found: ${ref}`);
    }
    const layers = await api.layers.list(entrypoint?.id);
    outputData(ctx, layers, (rows) => {
      printTable(
        rows.map((it) => ({
          id: it.id,
          name: it.name,
          entrypointId: it.entrypointId,
          parentId: it.parentId ?? "-",
          mounted: it.mountStatus,
          mountPath: it.mountPath,
        })),
      );
    });
  },
  create: async (ctx) => {
    const { flags, positional } = parseFlags(ctx.args);
    const unknown = hasUnknownFlags(flags, ["entrypoint", "name", "parent"]);
    if (unknown.length > 0) {
      throw new Error(`Unknown option(s): ${unknown.map((u) => `--${u}`).join(", ")}`);
    }

    const explicitEntrypointRef = getFlagString(flags, ["entrypoint"]);
    const explicitName = getFlagString(flags, ["name"]);
    const explicitParentRef = getFlagString(flags, ["parent"]);

    let entrypoint: Entrypoint | null = null;
    let name: string | undefined = explicitName;
    let parentRef: string | undefined = explicitParentRef;

    if (explicitEntrypointRef) {
      entrypoint = await resolveEntrypoint(explicitEntrypointRef);
      if (!entrypoint) {
        throw new Error(`Entrypoint not found: ${explicitEntrypointRef}`);
      }
      if (!name) name = positional[0];
      if (!parentRef) parentRef = positional[1];
    } else {
      const [first, second, third] = positional;
      const entrypoints = await api.entrypoints.list();
      if (first) {
        const candidate = selectByIdOrName(entrypoints, first) ?? selectByPath(entrypoints, first, "path");
        if (candidate) {
          entrypoint = candidate;
          if (!name) name = second;
          if (!parentRef) parentRef = third;
        } else {
          entrypoint = selectByPath(entrypoints, process.cwd(), "path");
          if (!name) name = first;
          if (!parentRef) parentRef = second;
        }
      } else {
        entrypoint = selectByPath(entrypoints, process.cwd(), "path");
      }
    }

    if (!entrypoint) {
      throw new Error("Unable to infer entrypoint for layer create. Provide --entrypoint or run from an entrypoint path.");
    }

    let parentId: string | null = null;
    if (parentRef) {
      const parent = await resolveLayer(parentRef, entrypoint);
      if (!parent) {
        throw new Error(`Parent layer not found: ${parentRef}`);
      }
      parentId = parent.id;
    }

    const payload: { entrypointId: string; name?: string; parentId?: string | null } = {
      entrypointId: entrypoint.id,
    };
    if (name && name.trim()) payload.name = name;
    if (parentId) payload.parentId = parentId;

    const layer = await api.layers.create(payload);
    outputData(ctx, layer, (value) => {
      console.log(`Created layer ${value.name} (${value.id})`);
      console.log(`Mount: ${value.mountPath}`);
    });
  },
  delete: async (ctx) => {
    const [ref] = ctx.args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }

    await confirmOrThrow(`Delete layer '${layer.name}' (${layer.id})?`, ctx.opts.force);
    await api.layers.delete(layer.id);
    console.log(`Deleted layer ${layer.id}.`);
  },
  mount: async ({ args }) => {
    const [ref] = args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.mount(layer.id);
    console.log(`Mounted layer ${layer.id}.`);
  },
  unmount: async ({ args }) => {
    const [ref] = args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.unmount(layer.id);
    console.log(`Unmounted layer ${layer.id}.`);
  },
  diff: async (ctx) => {
    const [ref] = ctx.args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    const diff = await api.layers.diff(layer.id);
    outputData(ctx, diff, (rows) => {
      printTable(rows.map((it) => ({ type: it.changeType, path: it.path })));
    });
  },
};

const mountCommands: Record<string, CommandHandler> = {
  list: async (ctx) => {
    const [ref] = ctx.args;
    const entrypoint = await resolveEntrypoint(ref);
    if (ref && !entrypoint) {
      throw new Error(`Entrypoint not found: ${ref}`);
    }
    const mounts = await api.userMounts.list(entrypoint?.id);
    outputData(ctx, mounts, (rows) => {
      printTable(
        rows.map((it) => ({
          id: it.id,
          name: it.name,
          entrypointId: it.entrypointId,
          layerId: it.attachedLayerId ?? "-",
          mounted: it.mountStatus,
          mountPath: it.mountPath,
        })),
      );
    });
  },
  create: async (ctx) => {
    const { flags, positional } = parseFlags(ctx.args);
    const unknown = hasUnknownFlags(flags, ["name", "path", "mount-path", "entrypoint", "layer"]);
    if (unknown.length > 0) {
      throw new Error(`Unknown option(s): ${unknown.map((u) => `--${u}`).join(", ")}`);
    }

    const name = getFlagString(flags, ["name"]) ?? positional[0];
    const mountPath = getFlagString(flags, ["path", "mount-path"]) ?? positional[1];
    const entryRef = getFlagString(flags, ["entrypoint"]) ?? positional[2];
    const layerRef = getFlagString(flags, ["layer"]) ?? positional[3];

    if (!name || !mountPath) {
      throw new Error("Usage: mount create <name> <mountPath> [entrypoint] [layer] [--entrypoint <ref>] [--layer <ref>]");
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

    outputData(ctx, mount, (value) => {
      console.log(`Created mount ${value.name} (${value.id})`);
      console.log(`Path: ${value.mountPath}`);
    });
  },
  delete: async (ctx) => {
    const [ref] = ctx.args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }

    await confirmOrThrow(`Delete mount '${mount.name}' (${mount.id})?`, ctx.opts.force);
    await api.userMounts.delete(mount.id);
    console.log(`Deleted mount ${mount.id}.`);
  },
  mount: async ({ args }) => {
    const [ref] = args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.mount(mount.id);
    console.log(`Mounted user mount ${mount.id}.`);
  },
  unmount: async ({ args }) => {
    const [ref] = args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.unmount(mount.id);
    console.log(`Unmounted user mount ${mount.id}.`);
  },
  attach: async ({ args }) => {
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

    await api.userMounts.attachLayer({ userMountId: mount.id, layerId });
    if (layerId) {
      console.log(`Attached layer ${layerId} to mount ${mount.id}.`);
    } else {
      console.log(`Detached layer from mount ${mount.id}.`);
    }
  },
};

const categoryCommands: Record<Category, Record<string, CommandHandler>> = {
  entrypoint: entrypointCommands,
  layer: layerCommands,
  mount: mountCommands,
  daemon: daemonCommands,
};

function printHelp(): void {
  console.log(`Spaces CLI

Commands:
  status
  remount
  entrypoint|ep|e <subcommand>
  layer|l <subcommand>
  mount|m <subcommand>
  daemon|d <subcommand>

Global options:
  --json       Print structured JSON output
  --force      Skip destructive-action confirmations

Examples:
  spaces status
  spaces entrypoint create --path /repo/base --name base
  spaces layer create --entrypoint ep_123 --name feat-login --parent lyr_123
  spaces mount create dev ~/worktree --entrypoint ep_123 --layer lyr_456
  spaces mount delete mnt_123 --force
  spaces daemon start

Run "spaces <category> help" to see subcommands.

Environment:
  SPACES_API_URL (default ${DAEMON_URL})
  SPACES_AUTH_TOKEN (optional)
  SPACES_DAEMON_PATH (optional)
  SPACES_STATE_DIR (optional)
`);
}

function printCategoryHelp(category: Category): void {
  if (category === "entrypoint") {
    console.log(`Entrypoint commands:
  entrypoint list [entrypoint|path]
  entrypoint create [path] [name] [--path <path>] [--name <name>]
  entrypoint delete [entrypoint|path] [--force]`);
    return;
  }

  if (category === "layer") {
    console.log(`Layer commands:
  layer list [entrypoint|path]
  layer create [entrypoint|path] [name] [parent]
      [--entrypoint <ref>] [--name <name>] [--parent <ref>]
  layer delete [layer|path] [--force]
  layer mount [layer|path]
  layer unmount [layer|path]
  layer diff [layer|path]`);
    return;
  }

  if (category === "daemon") {
    console.log(`Daemon commands:
  daemon status
  daemon start [args...]
  daemon stop
  daemon restart [args...]`);
    return;
  }

  console.log(`Mount commands:
  mount list [entrypoint|path]
  mount create <name> <mountPath> [entrypoint|path] [layer]
      [--name <name>] [--path <mountPath>] [--entrypoint <ref>] [--layer <ref>]
  mount delete [mount|path] [--force]
  mount mount [mount|path]
  mount unmount [mount|path]
  mount attach <mount|path> [layer]`);
}

function isConnectivityError(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  const msg = (error.message || "").toLowerCase();
  if (msg.includes("fetch failed")) return true;
  if (msg.includes("econnrefused")) return true;
  if (msg.includes("network")) return true;
  const cause = (error as { cause?: unknown }).cause;
  if (cause && typeof cause === "object") {
    const code = (cause as { code?: string }).code;
    if (code === "ECONNREFUSED" || code === "ENOTFOUND" || code === "EHOSTUNREACH") {
      return true;
    }
  }
  return false;
}

function printFriendlyError(error: unknown): void {
  if (isConnectivityError(error)) {
    console.error(`Cannot reach spaces daemon at ${DAEMON_URL}.`);
    console.error(`Try: spaces daemon start`);
    console.error(`Or set SPACES_API_URL if your daemon is elsewhere.`);
    return;
  }
  console.error(error instanceof Error ? error.message : String(error));
}

async function main(): Promise<void> {
  const parsed = parseGlobalOptions(process.argv.slice(2));
  const [first, second, ...rest] = parsed.args;

  if (!first || first === "help") {
    printHelp();
    return;
  }

  const rootHandler = rootCommands[first];
  if (rootHandler) {
    await rootHandler({ args: [second, ...rest].filter((arg): arg is string => arg !== undefined), opts: parsed.opts });
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

  await handler({ args: rest, opts: parsed.opts });
}

main().catch((error) => {
  printFriendlyError(error);
  process.exit(1);
});
