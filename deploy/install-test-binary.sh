#!/usr/bin/env bash
# Replace the isolated Tencent Cloud test Relay binary and restart only the
# test units. Never runs init. Never touches Relens / Beszel.
set -euo pipefail

SRC="${1:-/tmp/dsh-links-relay}"
DIR=/opt/dsh-links-relay-test
DEST="$DIR/dsh-links-relay"
CONTROL=dsh-links-relay-test-control
RELAY=dsh-links-relay-test-relay

if [[ "$(id -u)" -ne 0 ]]; then
  echo "run as root: sudo bash $0 $SRC" >&2
  exit 1
fi

if [[ ! -d "$DIR" || ! -f "$DIR/config.toml" ]]; then
  echo "test layout missing at $DIR; refusing to init" >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "binary not found: $SRC" >&2
  exit 1
fi

magic="$(od -An -tx1 -N 4 "$SRC" | tr -d ' \n')"
mach="$(od -An -tx1 -j 18 -N 2 "$SRC" | tr -d ' \n')"
if [[ "$magic" != "7f454c46" || "$mach" != "3e00" ]]; then
  echo "refusing non ELF x86-64 binary ($magic / $mach)" >&2
  exit 1
fi

if [[ -f "$DEST" ]]; then
  cp -a "$DEST" "$DEST.bak.$(date +%s)"
  ls -1t "$DEST".bak.* 2>/dev/null | tail -n +4 | xargs -r rm -f
fi

install -o dsh-relay-test -g dsh-relay-test -m 0755 "$SRC" "$DEST"
rm -f "$SRC"

# Runbook order: control first, then relay.
systemctl restart "$CONTROL"
systemctl restart "$RELAY"
systemctl is-active --quiet "$CONTROL"
systemctl is-active --quiet "$RELAY"

ss -lnt | grep -q ':8080' || { echo "control did not bind 8080" >&2; exit 1; }
ss -lnt | grep -q ':8443' || { echo "relay did not bind 8443" >&2; exit 1; }
ss -lnt | grep -q ':8444' || { echo "relay did not bind 8444" >&2; exit 1; }

# Leave the rest of the box alone.
if ss -lnt | grep -q ':43218'; then
  echo "Relens :43218 still listening"
fi
if ss -lnt | grep -q ':45876'; then
  echo "Beszel :45876 still listening"
fi

code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 3 http://127.0.0.1:8080/ || true)"
echo "control_ui_http=$code"
echo "installed $DEST units=$CONTROL,$RELAY"
