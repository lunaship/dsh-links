#!/bin/sh
set -eu

security_options=$(docker info --format '{{json .SecurityOptions}}')
case "$security_options" in
  *rootless*) ;;
  *) echo "Docker daemon is not rootless" >&2; exit 1 ;;
esac
case "$security_options" in
  *seccomp*) ;;
  *) echo "Docker seccomp is not enabled" >&2; exit 1 ;;
esac
if [ "$(docker info --format '{{.CgroupVersion}}')" != "2" ]; then
  echo "cgroup v2 is required for reliable resource limits" >&2
  exit 1
fi

docker compose config --quiet

control_id=$(docker compose ps -q control)
relay_id=$(docker compose ps -q relay)
if [ -z "$control_id" ] || [ -z "$relay_id" ]; then
  echo "both control and relay must be running" >&2
  exit 1
fi

for id in "$control_id" "$relay_id"; do
  if [ "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$id")" != "true" ]; then
    echo "$id root filesystem is not read-only" >&2
    exit 1
  fi
  if [ "$(docker inspect --format '{{.HostConfig.Privileged}}' "$id")" != "false" ]; then
    echo "$id is privileged" >&2
    exit 1
  fi
  user=$(docker inspect --format '{{.Config.User}}' "$id")
  case "$user" in
    ""|0|0:*) echo "$id runs as container root" >&2; exit 1 ;;
  esac
  cap_drop=$(docker inspect --format '{{json .HostConfig.CapDrop}}' "$id")
  case "$cap_drop" in
    *ALL*) ;;
    *) echo "$id does not drop all capabilities" >&2; exit 1 ;;
  esac
  security_opt=$(docker inspect --format '{{json .HostConfig.SecurityOpt}}' "$id")
  case "$security_opt" in
    *no-new-privileges*) ;;
    *) echo "$id does not enable no-new-privileges" >&2; exit 1 ;;
  esac
  if [ "$(docker inspect --format '{{.HostConfig.PidsLimit}}' "$id")" -le 0 ]; then
    echo "$id has no PID limit" >&2
    exit 1
  fi
  if [ "$(docker inspect --format '{{.HostConfig.Memory}}' "$id")" -le 0 ]; then
    echo "$id has no memory limit" >&2
    exit 1
  fi
  if [ "$(docker inspect --format '{{.HostConfig.NanoCpus}}' "$id")" -le 0 ]; then
    echo "$id has no CPU limit" >&2
    exit 1
  fi
  mounts=$(docker inspect --format '{{range .Mounts}}{{println .Destination}}{{end}}' "$id")
  case "$mounts" in
    *docker.sock*) echo "$id has a Docker socket mount" >&2; exit 1 ;;
  esac
done

relay_mounts=$(docker inspect --format '{{range .Mounts}}{{println .Destination}}{{end}}' "$relay_id")
case "$relay_mounts" in
  */var/lib/dsh-links-relay*) echo "Relay can mount Control data" >&2; exit 1 ;;
esac
case "$relay_mounts" in
  */run/secrets/issuer-private*|*/run/secrets/route-master*|*/run/secrets/admin-token*|*/run/secrets/admin-password*)
    echo "Relay has a Control-only secret mount" >&2
    exit 1
    ;;
esac

admin_host_ip=$(docker inspect --format '{{(index (index .HostConfig.PortBindings "8080/tcp") 0).HostIp}}' "$control_id")
if [ "$admin_host_ip" != "127.0.0.1" ]; then
  echo "Control port 8080 is not bound to host loopback" >&2
  exit 1
fi

echo "Hardened Docker runtime checks passed."
