#!/bin/sh
set -eu

source_dir=${1:-.local}
target_dir=${2:-.docker-secrets}

if [ -L "$source_dir" ] || [ ! -d "$source_dir" ]; then
  echo "source must be a real init directory: $source_dir" >&2
  exit 1
fi
if [ -e "$target_dir" ] || [ -L "$target_dir" ]; then
  echo "target must not already exist: $target_dir" >&2
  exit 1
fi

umask 077
mkdir "$target_dir"
chmod 0700 "$target_dir"

for name in issuer.key issuer.pub route-master.key admin.token admin.password ipc.auth relay.crt relay.key; do
  if [ ! -f "$source_dir/$name" ] || [ -L "$source_dir/$name" ]; then
    echo "missing or unsafe source file: $source_dir/$name" >&2
    exit 1
  fi
done

# File-backed Compose secrets retain source ownership and mode. Runtime
# containers use distinct non-root UIDs, so copies are readable inside a 0700
# host directory that other host users cannot traverse.
for name in issuer.key issuer.pub route-master.key admin.token admin.password ipc.auth relay.crt relay.key; do
  install -m 0444 "$source_dir/$name" "$target_dir/$name"
done

echo "Prepared Docker-only secret copies under $target_dir (directory mode 0700)."
