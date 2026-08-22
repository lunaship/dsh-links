# Deploy

Two layouts ship in this repo:

- **Combined (single user)** — `config.toml.example` + both units pointing at
  one config under one `dsh-relay` user. Fine for loopback/testing, but the
  relay process can then read the issuer private key and admin credentials.
- **Split users (recommended for any public deployment)** — control and relay
  run as separate users with separate configs. This is what the units in
  `systemd/` now assume. Setup below.

## Split-users layout

```
groupadd --system dsh-shared
useradd --system --gid dsh-shared --home-dir /nonexistent --shell /usr/sbin/nologin dsh-ctl
useradd --system --gid dsh-shared --home-dir /nonexistent --shell /usr/sbin/nologin dsh-relay

install -d -m 0755  /etc/dsh-links-relay
install -d -m 2770 -g dsh-shared /var/lib/dsh-links-relay /run/dsh-links-relay
```

`/run` is volatile; persist it with `/etc/tmpfiles.d/dsh-links-relay.conf`:

```
d /run/dsh-links-relay 2770 root dsh-shared -
```

File ownership (`chown` + `chmod`):

| Path                        | Owner:group      | Mode | Read by          |
|-----------------------------|------------------|------|------------------|
| `control.toml`              | `root:dsh-ctl`   | 0640 | control          |
| `issuer.key`                | `root:dsh-ctl`   | 0640 | control          |
| `admin.token`               | `root:dsh-ctl`   | 0640 | control          |
| `relay.toml`                | `root:dsh-relay` | 0640 | relay            |
| `relay.crt`                 | `root:dsh-relay` | 0644 | relay            |
| `relay.key`                 | `root:dsh-relay` | 0640 | relay            |
| `issuer.pub`                | `root:dsh-shared`| 0644 | relay            |
| `route-master.key`          | `root:dsh-shared`| 0640 | control + relay  |
| `ipc.auth`                  | `root:dsh-shared`| 0640 | control + relay  |
| `/var/lib/dsh-links-relay`  | `root:dsh-shared`| 2770 | control + relay  |
| `/run/dsh-links-relay`      | `root:dsh-shared`| 2770 | control + relay  |

Both services run `Group=dsh-shared`, so the control socket (created 0770 by
the control process) is connectable by the relay, and `route-master.key` /
`ipc.auth` are readable by both.

Then:

```
cp config-control.toml.example /etc/dsh-links-relay/control.toml   # fill in admin_password
cp config-relay.toml.example    /etc/dsh-links-relay/relay.toml
install -m 0755 dsh-links-relay /usr/local/bin/
cp systemd/*.service /etc/systemd/system/ && systemctl daemon-reload
systemctl enable --now dsh-links-relay-control.service dsh-links-relay-relay.service
```

## What this isolates — and what it cannot

A compromised relay process can no longer read the issuer private key (which
would allow minting perpetual capabilities) or the admin token/password
(control-plane takeover). Two residual trust points are inherent to the
current architecture:

- The relay needs `route-master.key` to verify CONNECT/BIND MACs, so it can
  derive route secrets for any route id.
- The shared database directory is group-writable because SQLite WAL readers
  must write the `-wal`/`-shm` sidecars; the relay could in principle tamper
  with the database file itself.

Removing those requires moving host lookups from the shared SQLite file onto
the authenticated IPC channel (a code change, tracked as future work). The
split layout still closes the reported finding: the highest-value secrets are
out of the relay's reach.

Generate key material with `dsh-links-relay init --dir /tmp/layout` on a
trusted machine and copy the files into place with the ownership above — the
local layout produced by `init` is the single-user combined one.
