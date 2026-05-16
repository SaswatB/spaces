# Release Updates

Spaces can update itself from GitHub Releases:

```bash
spaces update --check
spaces update
spaces update 0.1.0
```

The CLI also performs a quiet update check at most once every 24 hours after successful interactive commands. It skips JSON output, non-interactive shells, `spaces update`, and daemon lifecycle commands. Network failures are ignored.

To disable automatic checks:

```bash
SPACES_UPDATE_CHECK=0 spaces status
```

By default, the CLI checks `SaswatB/spaces` and installs the current platform's release assets into:

- the directory containing the running `spaces` binary, when running from an installed binary
- `$SPACES_BIN_DIR`, when set
- `~/.local/bin`, otherwise

The release must publish these assets for the current platform:

```text
spaces-<version>-<platform>-<arch>.tar.gz
SHA256SUMS-<platform>-<arch>
```

Supported platform suffixes are currently:

```text
darwin-arm64
darwin-x64
```

Linux artifacts are intentionally not published yet. The daemon still has macOS-specific mount management, so Linux is not a supported release target.

If `SHA256SUMS-<platform>-<arch>` is present, `spaces update` verifies the tarball before installing. Older releases without checksums can still be installed, but the command reports that checksums were not verified.

Useful options:

```bash
spaces update --repo owner/repo
spaces update --api-base-url https://api.github.com
spaces update --bin-dir ~/.local/bin
spaces update --force
```

For private repositories, set `SPACES_GITHUB_TOKEN` or `GITHUB_TOKEN`.

If the daemon is running, the updater downloads and verifies the new asset first, then stops the daemon, replaces `spaces`, `spacesd`, `spacesd-runtime`, and `spacesd-lib`, and restarts the daemon.

The install script can be used directly from GitHub:

```bash
curl -fsSL https://raw.githubusercontent.com/SaswatB/spaces/main/scripts/install.sh | bash
```

Installed releases include `spaces-uninstall` next to `spaces`:

```bash
spaces-uninstall
```
