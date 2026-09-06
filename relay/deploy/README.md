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
groupadd --system dsh-ctl
groupadd --system dsh-relay
useradd --system --gid dsh-ctl --groups dsh-shared --home-dir /nonexistent --shell /usr/sbin/nologin dsh-ctl
useradd --system --gid dsh-relay --groups dsh-shared --home-dir /nonexistent --shell /usr/sbin/nologin dsh-relay

install -d -m 0755  /etc/dsh-links-relay
install -d -m 0700 -o dsh-ctl -g dsh-ctl /var/lib/dsh-links-relay
install -d -m 2750 -o dsh-ctl -g dsh-shared /run/dsh-links-relay
```

`/run` is volatile; persist it with `/etc/tmpfiles.d/dsh-links-relay.conf`:

```
d /run/dsh-links-relay 2750 dsh-ctl dsh-shared -
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
| `route-master.key`          | `root:dsh-ctl`   | 0640 | control          |
| `ipc.auth`                  | `root:dsh-shared`| 0640 | control + relay  |
| `/var/lib/dsh-links-relay`  | `dsh-ctl:dsh-ctl`| 0700 | control          |
| `/run/dsh-links-relay`      | `dsh-ctl:dsh-shared`| 2750 | control write; relay connect |

Both services have `SupplementaryGroups=dsh-shared`, so the control socket
(created in the setgid shared runtime directory and chmod 0770) is connectable
by Relay and `ipc.auth` is readable by both. `route-master.key` and the
database remain Control-only.

Then:

```
cp config-control.toml.example /etc/dsh-links-relay/control.toml   # fill in admin_password
cp config-relay.toml.example    /etc/dsh-links-relay/relay.toml
install -m 0755 dsh-links-relay /usr/local/bin/
cp systemd/*.service /etc/systemd/system/ && systemctl daemon-reload
systemctl enable --now dsh-links-relay-control.service dsh-links-relay-relay.service
```

## What this isolates

A compromised relay process cannot read the issuer private key, administrator
credentials, route master key, or Control SQLite files. Host lookup and route
MAC verification are owned by Control and exposed only through bounded IPC.
Enrollment responses still pass one newly issued per-route secret through the
Relay process; end-to-end sealing that response would require a DLR/1 client
protocol change.

Generate key material with `dsh-links-relay init --dir /tmp/layout` on a
trusted machine and copy the files into place with the ownership above — the
local layout produced by `init` is the single-user combined one.
