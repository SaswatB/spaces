#!/usr/bin/env node
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { cac } from "cac";
import webPackage from "../package.json";
import { api } from "../src/lib/api";

type Entrypoint = Awaited<ReturnType<typeof api.entrypoints.list>>[number];
type Layer = Awaited<ReturnType<typeof api.layers.list>>[number];
type UserMount = Awaited<ReturnType<typeof api.userMounts.list>>[number];
type SystemStatus = Awaited<ReturnType<typeof api.system.status>>;

type Category = "entrypoint" | "layer" | "mount" | "daemon";
type MatchBy = { id: string; name: string; path?: string; mountPath?: string };

type CommonOptions = {
  json?: boolean;
  force?: boolean;
};

type UpdateOptions = CommonOptions & {
  check?: boolean;
  repo?: string;
  binDir?: string;
  apiBaseUrl?: string;
};

type GithubReleaseAsset = {
  name: string;
  browser_download_url: string;
};

type GithubRelease = {
  tag_name: string;
  html_url?: string;
  prerelease?: boolean;
  assets: GithubReleaseAsset[];
};

type UpdateCheckCache = {
  checkedAt: string;
  latestVersion?: string;
  releaseUrl?: string | null;
};

type SpacesConfig = {
  autoUpdateCheck: boolean;
};

type CommandContext = {
  args: string[];
  opts: CommonOptions;
};

type CommandHandler = (ctx: CommandContext) => Promise<void>;

const DAEMON_URL = process.env.SPACES_API_URL ?? "http://localhost:3100";
const CURRENT_VERSION = webPackage.version;
const DEFAULT_GITHUB_REPO = process.env.SPACES_GITHUB_REPOSITORY ?? "SaswatB/spaces";
const UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
const UPDATE_CHECK_TIMEOUT_MS = 1000;

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

function parseFlags(args: string[]): { flags: Record<string, string | boolean>; positional: string[] } {
  const flags: Record<string, string | boolean> = {};
  const positional: string[] = [];

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (!arg) continue;

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
    if (typeof value === "string" && value.trim()) return value;
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

function updateCheckCachePath(): string {
  return path.join(stateDir(), "update-check.json");
}

function configPath(): string {
  return path.join(stateDir(), "config.json");
}

function defaultConfig(): SpacesConfig {
  return { autoUpdateCheck: false };
}

function readConfig(): SpacesConfig {
  const defaults = defaultConfig();
  try {
    const filePath = configPath();
    if (!fs.existsSync(filePath)) return defaults;
    const parsed = JSON.parse(fs.readFileSync(filePath, "utf8")) as Partial<SpacesConfig>;
    return {
      autoUpdateCheck:
        typeof parsed.autoUpdateCheck === "boolean" ? parsed.autoUpdateCheck : defaults.autoUpdateCheck,
    };
  } catch {
    return defaults;
  }
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

function releasePlatformSuffix(): string {
  const platform = process.platform === "darwin" ? "darwin" : null;
  if (!platform) {
    throw new Error(`Unsupported OS for release updates: ${process.platform}. Spaces releases are currently macOS-only.`);
  }

  const arch =
    process.arch === "arm64" ? "arm64" : process.arch === "x64" ? "x64" : null;
  if (!arch) {
    throw new Error(`Unsupported architecture for release updates: ${process.arch}`);
  }

  return `${platform}-${arch}`;
}

function defaultBinDir(): string {
  if (process.env.SPACES_BIN_DIR) return process.env.SPACES_BIN_DIR;
  if (path.basename(process.execPath) === "spaces") return path.dirname(process.execPath);
  return path.join(os.homedir(), ".local", "bin");
}

function normalizeVersionTag(version: string): string {
  return version.startsWith("v") ? version : `v${version}`;
}

function versionFromTag(tag: string): string {
  return tag.startsWith("v") ? tag.slice(1) : tag;
}

function parseSemver(version: string): [number, number, number, string] | null {
  const match = version.match(/^v?(\d+)\.(\d+)\.(\d+)(.*)$/);
  if (!match) return null;
  return [
    Number.parseInt(match[1] ?? "0", 10),
    Number.parseInt(match[2] ?? "0", 10),
    Number.parseInt(match[3] ?? "0", 10),
    match[4] ?? "",
  ];
}

function compareVersions(a: string, b: string): number {
  const parsedA = parseSemver(a);
  const parsedB = parseSemver(b);
  if (!parsedA || !parsedB) return a.localeCompare(b);
  for (let i = 0; i < 3; i += 1) {
    const delta = (parsedA[i] as number) - (parsedB[i] as number);
    if (delta !== 0) return delta;
  }
  if (parsedA[3] === parsedB[3]) return 0;
  if (!parsedA[3]) return 1;
  if (!parsedB[3]) return -1;
  return parsedA[3].localeCompare(parsedB[3]);
}

function githubHeaders(): Record<string, string> {
  const token = process.env.SPACES_GITHUB_TOKEN ?? process.env.GITHUB_TOKEN;
  const headers: Record<string, string> = {
    Accept: "application/vnd.github+json",
    "User-Agent": `spaces/${CURRENT_VERSION}`,
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function fetchGithubRelease(
  repo: string,
  version: string | undefined,
  apiBaseUrl: string,
  signal?: AbortSignal,
): Promise<GithubRelease> {
  const trimmedBase = apiBaseUrl.replace(/\/+$/, "");
  const releasePath =
    !version || version === "latest"
      ? `/repos/${repo}/releases/latest`
      : `/repos/${repo}/releases/tags/${normalizeVersionTag(version)}`;
  const response = await fetch(`${trimmedBase}${releasePath}`, { headers: githubHeaders(), signal });
  if (!response.ok) {
    throw new Error(`Failed to fetch GitHub release ${version ?? "latest"}: HTTP ${response.status}`);
  }
  return (await response.json()) as GithubRelease;
}

function findReleaseAsset(release: GithubRelease, name: string): GithubReleaseAsset {
  const asset = release.assets.find((candidate) => candidate.name === name);
  if (!asset) {
    throw new Error(`Release ${release.tag_name} is missing asset ${name}`);
  }
  return asset;
}

async function downloadFile(url: string, target: string): Promise<void> {
  const response = await fetch(url, { headers: githubHeaders() });
  if (!response.ok) {
    throw new Error(`Failed to download ${url}: HTTP ${response.status}`);
  }
  const data = Buffer.from(await response.arrayBuffer());
  fs.writeFileSync(target, data);
}

function sha256File(filePath: string): string {
  const hash = createHash("sha256");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("hex");
}

function checksumForAsset(checksums: string, assetName: string): string | null {
  for (const line of checksums.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const parts = trimmed.split(/\s+/);
    const hash = parts[0];
    const name = parts[parts.length - 1]?.replace(/^\*/, "");
    if (hash && name === assetName) return hash;
  }
  return null;
}

async function verifyChecksumIfAvailable(
  release: GithubRelease,
  suffix: string,
  downloads: Array<{ name: string; path: string }>,
): Promise<boolean> {
  const checksumAsset =
    release.assets.find((asset) => asset.name === `SHA256SUMS-${suffix}`) ??
    release.assets.find((asset) => asset.name === "SHA256SUMS");
  if (!checksumAsset) return false;

  const response = await fetch(checksumAsset.browser_download_url, { headers: githubHeaders() });
  if (!response.ok) {
    throw new Error(`Failed to download SHA256SUMS: HTTP ${response.status}`);
  }
  const checksums = await response.text();
  for (const download of downloads) {
    const expected = checksumForAsset(checksums, download.name);
    if (!expected) {
      throw new Error(`SHA256SUMS does not include ${download.name}`);
    }
    const actual = sha256File(download.path);
    if (actual !== expected) {
      throw new Error(`Checksum mismatch for ${download.name}`);
    }
  }
  return true;
}

function extractTarball(archivePath: string, targetDir: string): void {
  const result = spawnSync("tar", ["-xzf", archivePath, "-C", targetDir], { stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error(`Failed to extract ${archivePath}`);
  }
}

function installFile(source: string, target: string): void {
  const tempTarget = `${target}.tmp-${process.pid}`;
  fs.copyFileSync(source, tempTarget);
  fs.chmodSync(tempTarget, 0o755);
  fs.renameSync(tempTarget, target);
}

function replaceDirectory(source: string, target: string): void {
  const tempTarget = `${target}.tmp-${process.pid}`;
  fs.rmSync(tempTarget, { recursive: true, force: true });
  fs.cpSync(source, tempTarget, { recursive: true });
  fs.rmSync(target, { recursive: true, force: true });
  fs.renameSync(tempTarget, target);
}

async function updateSpaces(version: string | undefined, opts: UpdateOptions): Promise<void> {
  const repo = opts.repo ?? DEFAULT_GITHUB_REPO;
  const apiBaseUrl = opts.apiBaseUrl ?? process.env.SPACES_GITHUB_API_BASE_URL ?? "https://api.github.com";
  const release = await fetchGithubRelease(repo, version, apiBaseUrl);
  const targetVersion = versionFromTag(release.tag_name);
  const suffix = releasePlatformSuffix();
  const updateAvailable = compareVersions(targetVersion, CURRENT_VERSION) > 0;
  const sameVersion = compareVersions(targetVersion, CURRENT_VERSION) === 0;

  if (opts.check) {
    outputData(
      buildCtx([], opts),
      {
        currentVersion: CURRENT_VERSION,
        latestVersion: targetVersion,
        updateAvailable,
        releaseUrl: release.html_url ?? null,
      },
      (value) => {
        if (value.updateAvailable) {
          console.log(`Update available: ${value.currentVersion} -> ${value.latestVersion}`);
        } else {
          console.log(`Spaces is up to date (${value.currentVersion}).`);
        }
        if (value.releaseUrl) console.log(value.releaseUrl);
      },
    );
    return;
  }

  if (sameVersion && !opts.force) {
    outputData(
      buildCtx([], opts),
      { ok: true, updated: false, currentVersion: CURRENT_VERSION, targetVersion },
      () => {
        console.log(`Spaces is already at ${targetVersion}. Re-run with --force to reinstall.`);
      },
    );
    return;
  }

  if (compareVersions(targetVersion, CURRENT_VERSION) < 0 && !opts.force) {
    throw new Error(`Refusing to downgrade ${CURRENT_VERSION} -> ${targetVersion}. Re-run with --force.`);
  }

  const assetName = `spaces-${targetVersion}-${suffix}.tar.gz`;
  const asset = findReleaseAsset(release, assetName);
  const binDir = path.resolve(opts.binDir ?? defaultBinDir());

  await confirmOrThrow(`Install Spaces ${targetVersion} to ${binDir}?`, opts.force);

  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "spaces-update-"));
  try {
    const releaseArchive = path.join(tempDir, assetName);
    await downloadFile(asset.browser_download_url, releaseArchive);
    const verified = await verifyChecksumIfAvailable(release, suffix, [
      { name: assetName, path: releaseArchive },
    ]);

    const extractDir = path.join(tempDir, "extract");
    fs.mkdirSync(extractDir, { recursive: true });
    extractTarball(releaseArchive, extractDir);

    const spacesPath = path.join(extractDir, "spaces");
    const uninstallPath = path.join(extractDir, "spaces-uninstall");
    const spacesdPath = path.join(extractDir, "spacesd");
    const runtimePath = path.join(extractDir, "spacesd-runtime");
    const libPath = path.join(extractDir, "spacesd-lib");
    if (!fs.existsSync(spacesPath)) throw new Error("Downloaded archive did not contain spaces");
    if (!fs.existsSync(uninstallPath)) throw new Error("Downloaded archive did not contain spaces-uninstall");
    if (!fs.existsSync(spacesdPath)) throw new Error("Downloaded archive did not contain spacesd");
    if (!fs.existsSync(runtimePath)) throw new Error("Downloaded archive did not contain spacesd-runtime");
    if (!fs.existsSync(libPath)) throw new Error("Downloaded archive did not contain spacesd-lib");

    const daemonStatus = daemonStatusPayload();
    const shouldRestartDaemon = daemonStatus.running;
    if (shouldRestartDaemon) {
      const stopped = await stopDaemon();
      if (!stopped.stopped) {
        throw new Error(stopped.message);
      }
    }

    fs.mkdirSync(binDir, { recursive: true });
    installFile(spacesPath, path.join(binDir, "spaces"));
    installFile(uninstallPath, path.join(binDir, "spaces-uninstall"));
    installFile(spacesdPath, path.join(binDir, "spacesd"));
    replaceDirectory(runtimePath, path.join(binDir, "spacesd-runtime"));
    replaceDirectory(libPath, path.join(binDir, "spacesd-lib"));

    if (shouldRestartDaemon) {
      await startDaemon();
    }

    outputData(
      buildCtx([], opts),
      {
        ok: true,
        updated: true,
        previousVersion: CURRENT_VERSION,
        version: targetVersion,
        binDir,
        checksumVerified: verified,
        daemonRestarted: shouldRestartDaemon,
      },
      (value) => {
        console.log(`Installed Spaces ${value.version} to ${value.binDir}.`);
        console.log(value.checksumVerified ? "Checksums verified." : "No SHA256SUMS asset was published for this release.");
        if (value.daemonRestarted) console.log("Daemon restarted.");
      },
    );
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

function readUpdateCheckCache(): UpdateCheckCache | null {
  try {
    const cachePath = updateCheckCachePath();
    if (!fs.existsSync(cachePath)) return null;
    return JSON.parse(fs.readFileSync(cachePath, "utf8")) as UpdateCheckCache;
  } catch {
    return null;
  }
}

function writeUpdateCheckCache(cache: UpdateCheckCache): void {
  try {
    ensureStateDir();
    fs.writeFileSync(updateCheckCachePath(), `${JSON.stringify(cache, null, 2)}\n`);
  } catch {
    // Update notices must never affect the command the user actually ran.
  }
}

function shouldSkipAutoUpdateCheck(args: unknown[]): boolean {
  const setting = (process.env.SPACES_UPDATE_CHECK ?? process.env.SPACES_AUTO_UPDATE_CHECK ?? "").toLowerCase();
  if (setting === "0" || setting === "false" || setting === "off" || setting === "no") return true;
  if (setting === "1" || setting === "true" || setting === "on" || setting === "yes") {
    // Environment override is useful for one-off checks and CI smoke tests.
  } else if (!readConfig().autoUpdateCheck) {
    return true;
  }
  if (!process.stderr.isTTY) return true;
  if (args.some((arg) => typeof arg === "object" && arg !== null && (arg as CommonOptions).json)) return true;

  const command = cli.args[0];
  if (!command) return true;
  if (command === "update") return true;
  if (command === "config") return true;
  if (command === "daemon" || command === "daemons" || command === "d") return true;
  return false;
}

function updateCheckIsFresh(cache: UpdateCheckCache | null): boolean {
  if (!cache?.checkedAt) return false;
  const checkedAt = Date.parse(cache.checkedAt);
  return Number.isFinite(checkedAt) && Date.now() - checkedAt < UPDATE_CHECK_INTERVAL_MS;
}

async function maybePrintUpdateNotice(args: unknown[]): Promise<void> {
  if (shouldSkipAutoUpdateCheck(args)) return;
  if (updateCheckIsFresh(readUpdateCheckCache())) return;

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPDATE_CHECK_TIMEOUT_MS);
  timeout.unref?.();

  try {
    const release = await fetchGithubRelease(
      DEFAULT_GITHUB_REPO,
      "latest",
      process.env.SPACES_GITHUB_API_BASE_URL ?? "https://api.github.com",
      controller.signal,
    );
    const latestVersion = versionFromTag(release.tag_name);
    writeUpdateCheckCache({
      checkedAt: new Date().toISOString(),
      latestVersion,
      releaseUrl: release.html_url ?? null,
    });
    if (compareVersions(latestVersion, CURRENT_VERSION) > 0) {
      console.error(`Spaces ${latestVersion} is available (current ${CURRENT_VERSION}). Run: spaces update`);
    }
  } catch {
    writeUpdateCheckCache({ checkedAt: new Date().toISOString() });
  } finally {
    clearTimeout(timeout);
  }
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

async function confirmOrThrow(prompt: string, force?: boolean): Promise<void> {
  if (force) return;

  if (!process.stdin.isTTY) {
    throw new Error("Refusing destructive action in non-interactive mode. Re-run with --force.");
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

function daemonStatusPayload(): { running: boolean; pid: number | null; stateDir: string; logFile: string } {
  const pid = readPid();
  const running = pid !== null && isRunning(pid);
  return {
    running,
    pid: running ? pid : null,
    stateDir: stateDir(),
    logFile: logFilePath(),
  };
}

async function stopDaemon(): Promise<{ stopped: boolean; message: string; pid: number | null }> {
  const pid = readPid();
  if (!pid) {
    return { stopped: false, message: "Daemon not running.", pid: null };
  }
  if (!isRunning(pid)) {
    fs.rmSync(pidFilePath(), { force: true });
    return { stopped: true, message: `Removed stale pid ${pid}.`, pid };
  }
  try {
    process.kill(pid, "SIGTERM");
  } catch (error) {
    return {
      stopped: false,
      message: `Failed to stop daemon: ${String(error)}`,
      pid,
    };
  }

  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (!isRunning(pid)) {
      fs.rmSync(pidFilePath(), { force: true });
      return { stopped: true, message: `Stopped daemon ${pid}.`, pid };
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }

  try {
    process.kill(pid, "SIGKILL");
  } catch (error) {
    return {
      stopped: false,
      message: `Failed to force stop daemon: ${String(error)}`,
      pid,
    };
  }
  fs.rmSync(pidFilePath(), { force: true });
  return { stopped: true, message: `Force stopped daemon ${pid}.`, pid };
}

async function startDaemon(args: string[] = []): Promise<{ alreadyRunning: boolean; pid: number; logFile: string }> {
  const pid = readPid();
  if (pid && isRunning(pid)) {
    return { alreadyRunning: true, pid, logFile: logFilePath() };
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
  return { alreadyRunning: false, pid: startedPid, logFile: logPath };
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

const daemonCommands = {
  status: async (ctx) => {
    const payload = daemonStatusPayload();
    outputData(ctx, payload, (value) => {
      if (value.running) {
        console.log(`Daemon running (pid ${value.pid}).`);
      } else {
        console.log("Daemon not running.");
      }
      console.log(`State dir: ${value.stateDir}`);
      console.log(`Log file: ${value.logFile}`);
    });
  },
  start: async (ctx) => {
    const result = await startDaemon(ctx.args);
    outputData(ctx, { ok: true, alreadyRunning: result.alreadyRunning, pid: result.pid, logFile: result.logFile }, (value) => {
      if (value.alreadyRunning) {
        console.log(`Daemon already running (pid ${value.pid}).`);
        return;
      }
      console.log(`Started daemon (pid ${value.pid}).`);
      console.log(`Logs: ${value.logFile}`);
    });
  },
  stop: async (ctx) => {
    const result = await stopDaemon();
    outputData(ctx, { ok: result.stopped, pid: result.pid, message: result.message }, (value) => {
      console.log(value.message);
    });
  },
  restart: async (ctx) => {
    const stopResult = await stopDaemon();
    await daemonCommands.start({ ...ctx, args: ctx.args });
    if (!ctx.opts.json) {
      console.log(stopResult.message);
    }
  },
} satisfies Record<"status" | "start" | "stop" | "restart", CommandHandler>;

const rootCommands = {
  status: async (ctx) => {
    const status = await api.system.status();
    outputData(ctx, status, printStatus);
  },
  remount: async (ctx) => {
    await api.system.remount();
    outputData(ctx, { ok: true }, () => {
      console.log("Remounted all layer and user mounts.");
    });
  },
  config: async (ctx) => {
    ensureStateDir();
    const config = readConfig();
    const data = {
      path: configPath(),
      exists: fs.existsSync(configPath()),
      config,
      defaults: defaultConfig(),
    };
    outputData(ctx, data, (value) => {
      console.log(`Config path: ${value.path}`);
      console.log(`Config file exists: ${value.exists ? "yes" : "no"}`);
      console.log(`autoUpdateCheck: ${value.config.autoUpdateCheck ? "on" : "off"}`);
    });
  },
} satisfies Record<"status" | "remount" | "config", CommandHandler>;

const entrypointCommands = {
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

    await confirmOrThrow(`Delete entrypoint '${entrypoint.name}' (${entrypoint.id})?`, ctx.opts.force);
    await api.entrypoints.delete(entrypoint.id);

    outputData(ctx, { ok: true, id: entrypoint.id }, (value) => {
      console.log(`Deleted entrypoint ${value.id}.`);
    });
  },
} satisfies Record<"list" | "create" | "delete", CommandHandler>;

const layerCommands = {
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

    const [first, second, third] = positional;

    let entrypoint: Entrypoint | null = null;
    let name: string | undefined = explicitName;
    let parentRef: string | undefined = explicitParentRef;

    if (explicitEntrypointRef) {
      entrypoint = await resolveEntrypoint(explicitEntrypointRef);
      if (!entrypoint) {
        throw new Error(`Entrypoint not found: ${explicitEntrypointRef}`);
      }
      if (!name) name = first;
      if (!parentRef) parentRef = second;
    } else {
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

    outputData(ctx, { ok: true, id: layer.id }, (value) => {
      console.log(`Deleted layer ${value.id}.`);
    });
  },
  mount: async (ctx) => {
    const [ref] = ctx.args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.mount(layer.id);
    outputData(ctx, { ok: true, id: layer.id }, (value) => {
      console.log(`Mounted layer ${value.id}.`);
    });
  },
  unmount: async (ctx) => {
    const [ref] = ctx.args;
    const layer = await resolveLayer(ref, null);
    if (!layer) {
      throw new Error(`Layer not found: ${ref ?? "(infer)"}`);
    }
    await api.layers.unmount(layer.id);
    outputData(ctx, { ok: true, id: layer.id }, (value) => {
      console.log(`Unmounted layer ${value.id}.`);
    });
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
} satisfies Record<"list" | "create" | "delete" | "mount" | "unmount" | "diff", CommandHandler>;

const mountCommands = {
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

    outputData(ctx, { ok: true, id: mount.id }, (value) => {
      console.log(`Deleted mount ${value.id}.`);
    });
  },
  mount: async (ctx) => {
    const [ref] = ctx.args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.mount(mount.id);
    outputData(ctx, { ok: true, id: mount.id }, (value) => {
      console.log(`Mounted user mount ${value.id}.`);
    });
  },
  unmount: async (ctx) => {
    const [ref] = ctx.args;
    const mount = await resolveUserMount(ref, null);
    if (!mount) {
      throw new Error(`Mount not found: ${ref ?? "(infer)"}`);
    }
    await api.userMounts.unmount(mount.id);
    outputData(ctx, { ok: true, id: mount.id }, (value) => {
      console.log(`Unmounted user mount ${value.id}.`);
    });
  },
  attach: async (ctx) => {
    const [mountRef, layerRef] = ctx.args;
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
    outputData(ctx, { ok: true, mountId: mount.id, layerId }, (value) => {
      if (value.layerId) {
        console.log(`Attached layer ${value.layerId} to mount ${value.mountId}.`);
      } else {
        console.log(`Detached layer from mount ${value.mountId}.`);
      }
    });
  },
} satisfies Record<"list" | "create" | "delete" | "mount" | "unmount" | "attach", CommandHandler>;

function printCategoryHelp(category: Category): void {
  if (category === "entrypoint") {
    console.log(`Entrypoint commands:\n  entrypoint list [entrypoint|path]\n  entrypoint create [path] [name] [--path <path>] [--name <name>]\n  entrypoint delete [entrypoint|path] [--force]`);
    return;
  }

  if (category === "layer") {
    console.log(`Layer commands:\n  layer list [entrypoint|path]\n  layer create [entrypoint|path] [name] [parent]\n      [--entrypoint <ref>] [--name <name>] [--parent <ref>]\n  layer delete [layer|path] [--force]\n  layer mount [layer|path]\n  layer unmount [layer|path]\n  layer diff [layer|path]`);
    return;
  }

  if (category === "daemon") {
    console.log(`Daemon commands:\n  daemon status\n  daemon start [args...]\n  daemon stop\n  daemon restart [args...]`);
    return;
  }

  console.log(`Mount commands:\n  mount list [entrypoint|path]\n  mount create <name> <mountPath> [entrypoint|path] [layer]\n      [--name <name>] [--path <mountPath>|--mount-path <mountPath>] [--entrypoint <ref>] [--layer <ref>]\n  mount delete [mount|path] [--force]\n  mount mount [mount|path]\n  mount unmount [mount|path]\n  mount attach <mount|path> [layer]`);
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

function withFriendlyErrors<Args extends unknown[]>(fn: (...args: Args) => Promise<void> | void) {
  return (...args: Args): void => {
    Promise.resolve(fn(...args))
      .then(() => maybePrintUpdateNotice(args))
      .catch((error) => {
        printFriendlyError(error);
        process.exit(1);
      });
  };
}

function buildCtx(rest: string[], opts: CommonOptions): CommandContext {
  return { args: rest, opts: { json: !!opts.json, force: !!opts.force } };
}

function optionArgsFromOpts(opts: Record<string, unknown>, knownKeys: string[]): string[] {
  const known = new Set([...knownKeys, "_", "--", "j", "f", "h", "help"]);
  const args: string[] = [];

  for (const [rawKey, rawValue] of Object.entries(opts)) {
    if (known.has(rawKey)) continue;
    if (!/^[a-zA-Z][a-zA-Z0-9]*$/.test(rawKey)) continue;
    if (rawValue === undefined || rawValue === null || rawValue === false) continue;

    const key = rawKey.replace(/[A-Z]/g, (char) => `-${char.toLowerCase()}`);
    const flag = `--${key}`;

    if (rawValue === true) {
      args.push(flag);
      continue;
    }

    args.push(flag, String(rawValue));
  }

  return args;
}

function dispatchCategory(
  category: Category,
  subcommand: string | undefined,
  rest: string[],
  opts: CommonOptions,
): Promise<void> {
  const map: Record<string, CommandHandler> =
    category === "entrypoint"
      ? entrypointCommands
      : category === "layer"
      ? layerCommands
      : category === "mount"
      ? mountCommands
      : daemonCommands;

  if (!subcommand || subcommand === "help") {
    printCategoryHelp(category);
    return Promise.resolve();
  }

  const handler = map[subcommand];
  if (!handler) {
    throw new Error(`Unknown subcommand: ${category} ${subcommand}`);
  }

  return handler(buildCtx(rest, opts));
}

const cli = cac("spaces");

cli
  .version(CURRENT_VERSION)
  .option("-j, --json", "Print structured JSON output")
  .option("-f, --force", "Skip destructive-action confirmations")
  .help();

cli.command("status", "Show daemon/system status").action(
  withFriendlyErrors(async (opts: CommonOptions) => {
    await rootCommands.status(buildCtx([], opts));
  }),
);

cli.command("remount", "Remount all layers and user mounts").action(
  withFriendlyErrors(async (opts: CommonOptions) => {
    await rootCommands.remount(buildCtx([], opts));
  }),
);

cli.command("config", "Show Spaces config and config file path").action(
  withFriendlyErrors(async (opts: CommonOptions) => {
    await rootCommands.config(buildCtx([], opts));
  }),
);

cli
  .command("update [version]", "Install or check for updates from GitHub Releases")
  .option("--check", "Only check whether an update is available")
  .option("--repo <owner/repo>", "GitHub repository to use", { default: DEFAULT_GITHUB_REPO })
  .option("--api-base-url <url>", "GitHub API base URL")
  .option("--bin-dir <path>", "Installation directory")
  .action(
    withFriendlyErrors(async (version: string | undefined, opts: UpdateOptions) => {
      await updateSpaces(version, opts);
    }),
  );

cli
  .command("entrypoint [subcommand] [...rest]", "Entrypoint operations")
  .alias("entrypoints")
  .alias("ep")
  .alias("e")
  .allowUnknownOptions()
  .action(
    withFriendlyErrors(async (subcommand: string | undefined, rest: string[] = [], opts: CommonOptions) => {
      const merged = [...rest, ...optionArgsFromOpts(opts as Record<string, unknown>, ["json", "force"])];
      await dispatchCategory("entrypoint", subcommand, merged, opts);
    }),
  );

cli
  .command("layer [subcommand] [...rest]", "Layer operations")
  .alias("layers")
  .alias("l")
  .allowUnknownOptions()
  .action(
    withFriendlyErrors(async (subcommand: string | undefined, rest: string[] = [], opts: CommonOptions) => {
      const merged = [...rest, ...optionArgsFromOpts(opts as Record<string, unknown>, ["json", "force"])];
      await dispatchCategory("layer", subcommand, merged, opts);
    }),
  );

cli
  .command("mount [subcommand] [...rest]", "User mount operations")
  .alias("mounts")
  .alias("m")
  .allowUnknownOptions()
  .action(
    withFriendlyErrors(async (subcommand: string | undefined, rest: string[] = [], opts: CommonOptions) => {
      const merged = [...rest, ...optionArgsFromOpts(opts as Record<string, unknown>, ["json", "force"])];
      await dispatchCategory("mount", subcommand, merged, opts);
    }),
  );

cli
  .command("daemon [subcommand] [...rest]", "Daemon process operations")
  .alias("daemons")
  .alias("d")
  .allowUnknownOptions()
  .action(
    withFriendlyErrors(async (subcommand: string | undefined, rest: string[] = [], opts: CommonOptions) => {
      const merged = [...rest, ...optionArgsFromOpts(opts as Record<string, unknown>, ["json", "force"])];
      await dispatchCategory("daemon", subcommand, merged, opts);
    }),
  );

cli.example("status");
cli.example("entrypoint create --path /repo/base --name base");
cli.example("layer create --entrypoint ep_123 --name feat-login --parent lyr_123");
cli.example("mount create dev ~/worktree --entrypoint ep_123 --layer lyr_456");
cli.example("mount delete mnt_123 --force");
cli.example("daemon start");
cli.example("config");
cli.example("update --check");

cli.on("command:*", () => {
  const raw = cli.args.join(" ").trim();
  printFriendlyError(new Error(`Unknown command: ${raw || "(empty)"}`));
  console.log();
  cli.outputHelp();
  process.exit(1);
});

if (process.argv.length <= 2) {
  cli.outputHelp();
  process.exit(0);
}

cli.parse(process.argv);
