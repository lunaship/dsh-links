#!/bin/sh
set -eu
DATA="${RELAY_DATA:-/data}"
HOST="${RELAY_HOST:-relay.dshlinks.com}"

if [ ! -f "$DATA/config.toml" ]; then
  echo "initializing relay data dir $DATA (host $HOST)"
  dsh-links-relay init --dir "$DATA" --listen-all --host "$HOST"
  echo "admin password is in $DATA/config.toml (admin_password). Change it after first login."
fi

wait_for_control_sock() {
  i=0
  while [ ! -S "$DATA/control.sock" ]; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
      echo "control socket $DATA/control.sock not ready" >&2
      exit 1
    fi
    sleep 0.5
  done
}

# OpenShip wraps service `command` as `sh -c "..."`. Pass that through so
# `dsh-links-relay control|relay ...` actually runs instead of becoming
# `dsh-links-relay sh`.
if [ "${1:-}" = "sh" ] || [ "${1:-}" = "/bin/sh" ] || [ "${1:-}" = "bash" ] || [ "${1:-}" = "/bin/bash" ]; then
  case " $* " in
    *" relay "*) wait_for_control_sock ;;
  esac
  exec "$@"
fi

# If someone (or compose) already prefixed the binary name, drop the duplicate.
if [ "${1:-}" = "dsh-links-relay" ]; then
  shift
fi

role="${1:-control}"
if [ "$role" = "relay" ]; then
  wait_for_control_sock
fi

if [ "$#" -eq 0 ]; then
  exec dsh-links-relay control --config "$DATA/config.toml"
fi
exec dsh-links-relay "$@"
