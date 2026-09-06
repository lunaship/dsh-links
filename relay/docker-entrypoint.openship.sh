#!/bin/sh
set -eu
DATA="${RELAY_DATA:-/data}"
SHARE="${RELAY_SHARE:-/relay}"
HOST="${RELAY_HOST:-relay.dshlinks.com}"
role="${1:-control}"

rewrite_control_socket() {
  cfg="$1"
  sock="$2"
  tmp="$cfg.tmp"
  awk -v sock="$sock" '
    BEGIN { done = 0 }
    $0 ~ /^control_socket[[:space:]]*=/ {
      print "control_socket = \"" sock "\""
      done = 1
      next
    }
    { print }
    END { if (!done) print "control_socket = \"" sock "\"" }
  ' "$cfg" > "$tmp"
  mv "$tmp" "$cfg"
  chmod 0600 "$cfg"
}

publish_relay_share() {
  mkdir -p "$SHARE"
  chmod 0750 "$SHARE"
  cp -f "$DATA/issuer.pub" "$SHARE/issuer.pub"
  cp -f "$DATA/relay.crt" "$SHARE/relay.crt"
  cp -f "$DATA/relay.key" "$SHARE/relay.key"
  chmod 0600 "$SHARE/relay.key"
  cp -f "$DATA/ipc.auth" "$SHARE/ipc.auth"
  chmod 0600 "$SHARE/ipc.auth"
  rewrite_control_socket "$DATA/config.toml" "$SHARE/control.sock"
  cat > "$SHARE/config.toml" <<EOF
# OpenShip relay-only config. Control secrets stay on the control volume.
client_listen = "0.0.0.0:8443"
agent_listen = "0.0.0.0:8444"
control_socket = "$SHARE/control.sock"
ipc_auth_token_file = "$SHARE/ipc.auth"
tls_cert = "$SHARE/relay.crt"
tls_key = "$SHARE/relay.key"
issuer_public_key = "$SHARE/issuer.pub"
public_host = "$HOST"
max_total_streams = 1000
default_max_streams_per_route = 8
max_conns = 2000
bridge_max_lifetime = "30m"
heartbeat_interval = "20s"
agent_dead_after = "65s"
bind_timeout = "10s"
EOF
  chmod 0600 "$SHARE/config.toml"
}

if [ "$role" = "relay" ]; then
  if [ -e "$DATA/issuer.key" ] || [ -e "$DATA/route-master.key" ] || [ -e "$DATA/control.db" ] || [ -e "$DATA/admin.password" ]; then
    echo "OpenShip relay refused to start: Control secrets are visible at $DATA" >&2
    echo "Use docker-compose.openship.yml with split volumes, or docker-compose.yml for production." >&2
    exit 1
  fi
  i=0
  while [ ! -S "$SHARE/control.sock" ]; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
      echo "control socket $SHARE/control.sock not ready" >&2
      exit 1
    fi
    sleep 0.5
  done
  exec dsh-links-relay relay --config "$SHARE/config.toml"
fi

mkdir -p "$DATA"
chmod 0700 "$DATA"
if [ ! -f "$DATA/config.toml" ]; then
  echo "initializing relay data dir $DATA (host $HOST)"
  dsh-links-relay init --dir "$DATA" --listen-all --host "$HOST"
  echo "admin password is in $DATA/admin.password"
elif ! grep -q '^public_host' "$DATA/config.toml"; then
  # Existing volumes were created before enroll URIs. Keep keys; just record
  # the hostname the plugin should dial.
  printf '\npublic_host = "%s"\n' "$HOST" >> "$DATA/config.toml"
fi
publish_relay_share
exec dsh-links-relay control --config "$DATA/config.toml"
