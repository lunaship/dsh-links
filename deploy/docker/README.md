# Hardened dual-container deployment

This is the recommended public deployment. Runtime consists of exactly two
containers with different numeric UIDs, separate networks, separate configs,
and no shared persistent data volume. Relay can connect to the Control Unix
socket but cannot mount Control's database, issuer key, administrator secrets,
or route master key.

## Host prerequisites

- Linux with cgroup v2.
- Rootless Docker Engine and Compose v2.
- No rootful Docker socket left enabled.
- Only the dedicated deployment account can access the rootless Docker socket.

Confirm `docker info` reports `rootless`, `seccomp`, and a working cgroup
driver before deployment. Do not expose the Docker API over TCP.

## Bootstrap

Generate the initial material outside the runtime containers:

```sh
umask 077
mkdir -m 0700 .local
go run ./cmd/dsh-links-relay init --dir .local --listen-all --host relay.example.com
./deploy/docker/prepare-secrets.sh .local .docker-secrets
```

Replace `.docker-secrets/relay.crt` and `.docker-secrets/relay.key` with the
public certificate and private key used by the Relay endpoint. Keep the
`.docker-secrets` directory mode at `0700`.

File-backed Compose secrets preserve their source mode. `prepare-secrets.sh`
therefore creates read-only `0444` copies inside a host directory that only the
deployment account can traverse. This allows the distinct non-root container
UIDs to read their granted files without making them accessible to other host
users.

## Start

```sh
docker compose build --pull
docker compose config >/dev/null
docker compose up -d
docker compose ps
./deploy/docker/verify-runtime.sh
```

The TLS-protected Control UI is published only at `https://127.0.0.1:8080`;
ports 8443 and 8444 are the only public listeners. The generated certificate
includes the loopback SANs; use an SSH tunnel for remote administration.

For released images, set `DSH_RELAY_IMAGE` to an immutable digest and omit
`--build`, for example `registry.example/dsh-links-relay@sha256:...`.

## Runtime security checks

```sh
docker compose exec relay sh
```

The command above must fail because the runtime image has no shell. Inspect
the container metadata instead:

```sh
docker inspect dsh-links-relay-relay-1 dsh-links-relay-control-1
```

`verify-runtime.sh` checks Rootless Docker, seccomp, cgroup v2, non-root UIDs,
read-only root filesystems, dropped capabilities, absence of Docker socket
mounts, host-loopback Control publishing, and absence of the Control data mount
from Relay. Keep the manual `docker inspect` review as a second check.

## Backup and rollback

Back up `control-data` with SQLite's online backup mechanism and retain an
encrypted offline copy of `.local`. A rollback must reuse the existing
database and keys; never silently regenerate them.
