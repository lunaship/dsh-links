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
# 必须提供公网域名：控制台才能生成完整接入串与二维码（见下方说明）
export DSH_RELAY_PUBLIC_HOST=relay.example.com
docker compose build --pull
docker compose config >/dev/null
docker compose up -d
docker compose ps
./deploy/docker/verify-runtime.sh
```

`DSH_RELAY_PUBLIC_HOST` 注入 control 容器的 `RELAY_HOST` 环境变量，作为接入串的主机名（也可直接在 `deploy/docker/control.toml` 填写 `public_host`）。**两个都不填时，控制台只能创建裸邀请码，无法生成完整接入串与二维码**——测试者将无法使用「手机扫码搬运」流程。

The TLS-protected Control UI is published only at `https://127.0.0.1:8080`;
ports 8443 and 8444 are the only public listeners. The generated certificate
includes the loopback SANs; use an SSH tunnel for the `admin` operator.

Hosted Control tenants (not App accounts) log in to the same UI. Put a TLS
reverse proxy on the host in front of `127.0.0.1:8080` — do not publish 8080
on `0.0.0.0`. This Compose file already gives Control TLS, so session cookies
are Secure. If a proxy talks **HTTP** to loopback Control instead, set
`admin_secure_cookies = true`. Example Caddy snippet:

```
control.example.com {
    reverse_proxy https://127.0.0.1:8080 {
        transport http {
            tls_insecure_skip_verify
        }
    }
}
```

Provision tenants on the host after Control is up:

```sh
dsh-links-relay tenant create --config .local/config.toml --login alice --password-file ./alice.pass
```

If `public_control_url` is set, the command prints that HTTPS Control URL for the handoff. There is no public signup. The Android App still pairs with the plugin.

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
