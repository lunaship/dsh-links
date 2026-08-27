#!/usr/bin/env bash
set -euo pipefail
umask 077

# Snapshot-only collector. It reports measurements; it does not turn targets
# into claims and it does not contact a remote host.
pid="${1:-}"
out="${2:-./capacity-$(date -u +%Y%m%dT%H%M%SZ).txt}"
if [[ -z "$pid" || ! "$pid" =~ ^[0-9]+$ ]]; then
  echo "usage: $0 <relay-pid> [output-file]" >&2
  exit 2
fi
if ! kill -0 "$pid" 2>/dev/null; then
  echo "relay pid is not running: $pid" >&2
  exit 2
fi
{
  printf 'timestamp_utc=%s\n' "$(date -u +%FT%TZ)"
  printf 'pid=%s\n' "$pid"
  # Do not include the command line: Relay arguments can contain paths or
  # credentials that do not belong in a shareable capacity snapshot.
  ps -o pid=,ppid=,etime=,rss=,pcpu=,state= -p "$pid"
  if command -v lsof >/dev/null 2>&1; then
    if fd_count="$(lsof -p "$pid" 2>/dev/null | awk 'NR > 1 { count++ } END { print count + 0 }')"; then
      printf 'open_fd_count=%s\n' "$fd_count"
    else
      printf 'open_fd_count=unavailable\n'
    fi
  else
    printf 'open_fd_count=unavailable\n'
  fi
  printf 'disk_usage='; df -Pk . | tail -n 1
  printf 'thresholds=fd<70%%, active_streams<900, error_rate<0.1%%, db_disk<80%% (targets only; not measured here)\n'
} > "$out"
printf 'Wrote %s\n' "$out"
