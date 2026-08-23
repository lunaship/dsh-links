#!/bin/sh
set -eu
DATA="${RELAY_DATA:-/data}"
HOST="${RELAY_HOST:-relay.dshlinks.com}"
mkdir -p "$DATA"
chmod 0700 "$DATA"
if [ ! -f "$DATA/config.toml" ]; then
  echo "initializing relay data dir $DATA (host $HOST)"
  dsh-links-relay init --dir "$DATA" --listen-all --host "$HOST"
  echo "admin password is in $DATA/admin.password"
fi
role="${1:-control}"
if [ "$role" = "relay" ]; then
  i=0
  while [ ! -S "$DATA/control.sock" ]; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
      echo "control socket $DATA/control.sock not ready" >&2
      exit 1
    fi
    sleep 0.5
  done
fi
exec dsh-links-relay "$role" --config "$DATA/config.toml"
