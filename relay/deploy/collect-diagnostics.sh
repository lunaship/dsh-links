#!/usr/bin/env bash
set -euo pipefail
umask 077

# Safe-by-default metadata export. It intentionally does not read config,
# SQLite, environment variables, request bodies, or log files.
pid="${1:-}"
out="${2:-./diagnostic-$(date -u +%Y%m%dT%H%M%SZ).txt}"
if [[ -z "$pid" || ! "$pid" =~ ^[0-9]+$ ]]; then
  echo "usage: $0 <relay-pid> [output-file]" >&2
  exit 2
fi
if ! kill -0 "$pid" 2>/dev/null; then
  echo "relay pid is not running: $pid" >&2
  exit 2
fi
{
  printf 'schema=DSH-Links-diagnostic-v1\n'
  printf 'timestamp_utc=%s\n' "$(date -u +%FT%TZ)"
  printf 'pid=%s\n' "$pid"
  ps -o pid=,ppid=,etime=,rss=,pcpu=,state= -p "$pid"
  printf 'working_directory='; pwd
  printf 'disk_usage='; df -Pk . | tail -n 1
  if command -v lsof >/dev/null 2>&1; then
    if fd_count="$(lsof -p "$pid" 2>/dev/null | awk 'NR > 1 { count++ } END { print count + 0 }')"; then
      printf 'open_fd_count=%s\n' "$fd_count"
    else
      printf 'open_fd_count=unavailable\n'
    fi
  else
    printf 'open_fd_count=unavailable\n'
  fi
  printf 'sensitive_fields=omitted\n'
} > "$out"
printf 'Wrote %s (metadata only; do not append raw logs)\n' "$out"
