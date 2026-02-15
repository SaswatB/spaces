# Spaces JVM daemon

Exposes a minimal HTTP API and includes an NFSv4 server wiring using dCache nfs4j/oncrpc4j with a prototype VFS.

## Running (dev)

```bash
gradle -p packages/daemon-jvm run
```

Environment variables:

- SPACES_DATA_DIR
- SPACES_DB_PATH
- SPACES_API_HOST
- SPACES_API_PORT
- SPACES_NFS_HOST
- SPACES_NFS_PORT
