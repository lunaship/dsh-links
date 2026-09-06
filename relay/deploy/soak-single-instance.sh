#!/usr/bin/env bash
set -euo pipefail
umask 077

# Run a single local Relay command under a measured 24-hour window. This does
# not contact or modify a remote server. A shorter run is explicitly labelled
# exploratory and cannot be used as RC1 evidence.
duration="${RELAY_SOAK_SECONDS:-86400}"
sample_seconds="${RELAY_SOAK_SAMPLE_SECONDS:-60}"
if [[ ! "$duration" =~ ^[1-9][0-9]*$ || ! "$sample_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "RELAY_SOAK_SECONDS and RELAY_SOAK_SAMPLE_SECONDS must be positive integers" >&2
  exit 2
fi
if [[ "$duration" != "86400" && "${ALLOW_SHORT_SOAK:-0}" != "1" ]]; then
  echo "Refusing non-24h soak; set ALLOW_SHORT_SOAK=1 for exploratory evidence" >&2
  exit 2
fi
if [[ "${1:-}" == "--" ]]; then
  shift
fi
if (( $# == 0 )); then
  echo "usage: $0 -- <relay command and arguments>" >&2
  exit 2
fi

out="${RELAY_SOAK_OUTPUT:-$PWD/.local/soak-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$out"
printf 'started_utc=%s\nduration_seconds=%s\ncommand_executable=%s\ncommand_arguments=omitted\nprocess_log_sensitivity=private-unredacted\n' \
  "$(date -u +%FT%TZ)" "$duration" "${1##*/}" > "$out/metadata.txt"

"$@" >"$out/process.log" 2>&1 &
pid=$!
cleanup() {
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    sleep 5
    kill -KILL "$pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

end=$((SECONDS + duration))
completed_window=0
while kill -0 "$pid" 2>/dev/null; do
  now="$(date -u +%FT%TZ)"
  {
    printf 'timestamp=%s\n' "$now"
    ps -o pid=,ppid=,etime=,rss=,pcpu=,state= -p "$pid" || true
    if command -v lsof >/dev/null 2>&1; then
      if fd_count="$(lsof -p "$pid" 2>/dev/null | awk 'NR > 1 { count++ } END { print count + 0 }')"; then
        printf 'open_fds=%s\n' "$fd_count"
      else
        printf 'open_fds=unavailable\n'
      fi
    fi
    df -Pk "$out" | tail -n 1
  } >> "$out/metrics.log"
  if (( SECONDS >= end )); then
    completed_window=1
    break
  fi
  remaining=$((end - SECONDS))
  sleep_for="$sample_seconds"
  if (( remaining < sleep_for )); then sleep_for="$remaining"; fi
  sleep "$sleep_for"
done

if (( completed_window == 0 )); then
  status=0
  wait "$pid" || status=$?
  printf 'finished_utc=%s\nexit_status=%s\nclassification=failed-early-exit\n' \
    "$(date -u +%FT%TZ)" "$status" >> "$out/metadata.txt"
  echo "Relay command exited before the requested soak window completed" >&2
  exit 1
fi

# The command is expected to be a long-running Relay. Stop it after the
# requested window and require a bounded shutdown so this harness terminates.
kill -TERM "$pid" 2>/dev/null || true
shutdown_deadline=$((SECONDS + 30))
while kill -0 "$pid" 2>/dev/null && (( SECONDS < shutdown_deadline )); do
  sleep 1
done
forced_kill=0
if kill -0 "$pid" 2>/dev/null; then
  forced_kill=1
  kill -KILL "$pid" 2>/dev/null || true
fi
status=0
wait "$pid" || status=$?
if [[ "$duration" == "86400" ]]; then
  classification="completed-24h-window"
else
  classification="completed-exploratory-short-run"
fi
printf 'finished_utc=%s\nexit_status_after_harness_stop=%s\nforced_kill=%s\nclassification=%s\n' \
  "$(date -u +%FT%TZ)" "$status" "$forced_kill" "$classification" >> "$out/metadata.txt"
if (( forced_kill != 0 )); then
  echo "Relay did not stop within 30 seconds after the soak window" >&2
  exit 1
fi
