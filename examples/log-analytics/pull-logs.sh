#!/usr/bin/env bash
#
# Pull Frontcache logs from the FC servers into the logs/ drop zone beside it, where the
# Logstash container tails them.
#
# Hosts are ssh aliases from your ~/.ssh/config (e.g. fc-us.hobbyray.com), or any
# plain [user@]hostname. Pass them as arguments or via the HOSTS env var:
#
#   ./pull-logs.sh fc-us.hobbyray.com fc-eu.hobbyray.com
#   ./pull-logs.sh "fc-us.hobbyray.com fc-eu.hobbyray.com"
#   HOSTS="fc-us.hobbyray.com fc-eu.hobbyray.com" ./pull-logs.sh
#
# For each host it rsyncs the current log plus any rolled .zip archives from the
# remote log directory, unzips the archives, prefixes every file with the host alias
# (so hosts never collide), and drops them into logs/.
#
# Log types pulled:
#   - frontcache-requests*.log  -> request logs (Frontcache Overview dashboard)
#   - error*.log                -> error logs (Frontcache Errors dashboard)
#   - fallback*.log             -> fallback logs (Frontcache Fallbacks dashboard)
#   - frontcache-failed-requests*.log
#                               -> rejected / failed request logs
#                                  (Frontcache Rejected Requests dashboard)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- configuration (env-overridable) ----------------------------------------
# every argument is a host; a single quoted "a b c" argument still works.
if [ "$#" -gt 0 ]; then
  HOSTS="$*"
else
  HOSTS="${HOSTS:-}"
fi
# Where the logs are on the remote host. Left empty, it is auto-detected per host
# from LOG_DIR_CANDIDATES below -- Frontcache writes to $FRONTCACHE_HOME/logs, and
# where that is depends on how it was installed. Set REMOTE_LOG_DIR to pin it
# (then a host that does not have it is an error, not a fallback).
REMOTE_LOG_DIR="${REMOTE_LOG_DIR:-}"
# Probed in order, on the remote host. The '~' entry is quoted so it reaches the
# remote shell as a tilde -- unquoted, bash would expand it against the LOCAL home.
LOG_DIR_CANDIDATES=(
  /opt/frontcache/FRONTCACHE_HOME/logs             # installer script (the default --dir)
  '~/opt/frontcache-server/FRONTCACHE_HOME/logs'   # archive unpacked into the ssh user's home
  /opt/frontcache-server/FRONTCACHE_HOME/logs      # archive unpacked under /opt
  /opt/frontcache/logs                             # logs symlinked/relocated out of FRONTCACHE_HOME
)
DEST_DIR="${DEST_DIR:-$SCRIPT_DIR/logs}"
STAGE_DIR="${STAGE_DIR:-${TMPDIR:-/tmp}/fc-pull-logs}"
# Directory for the ssh connection-multiplexing sockets. Kept short on purpose
# (see the ControlPath note below); not $TMPDIR, which is long on macOS.
SSH_CTL_DIR="${SSH_CTL_DIR:-/tmp}"
# The systemd unit runs Frontcache as the ssh user itself (User=$REMOTE_USER), so
# that user owns its own logs and plain rsync reads them. Set RSYNC_SUDO=1 only for
# a deployment running under a different service account -- and note it then needs
# passwordless sudo, since rsync-over-ssh has no tty to prompt on.
RSYNC_SUDO="${RSYNC_SUDO:-0}"
# -----------------------------------------------------------------------------

if [ -z "$HOSTS" ]; then
  echo "ERROR: no hosts given. Usage: $0 fc-us.hobbyray.com [fc-eu.hobbyray.com ...]" >&2
  echo "       (ssh aliases from ~/.ssh/config, or plain [user@]hostname)" >&2
  exit 1
fi

for bin in rsync unzip ssh; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "ERROR: $bin not found." >&2
    exit 1
  fi
done

RSYNC_OPTS=(-az --itemize-changes --prune-empty-dirs)
if [ "$RSYNC_SUDO" = "1" ]; then
  # -n so a sudo password requirement fails fast with a readable message instead
  # of hanging on a prompt that has no tty to appear on.
  RSYNC_OPTS+=(--rsync-path="sudo -n rsync")
fi

# Log file patterns to pull. Passed to rsync as filters (not remote shell globs)
# so that "nothing matches this pattern" is simply an empty transfer rather than
# an error, leaving genuine failures -- bad path, auth, sudo -- clearly visible.
LOG_PATTERNS=(
  "frontcache-requests*.log"
#  "frontcache-requests*.log.zip"
  "error*.log"
#  "error*.log.zip"
  "fallback*.log"
#  "fallback*.log.zip"
  # rejected/failed requests: written by the 'frontcache.failed-requests' logger.
  # NOTE: this name does not match "frontcache-requests*", so it needs its own
  # pattern -- and the fc-requests Logstash pipeline likewise never sees it.
  "frontcache-failed-requests*.log"
#  "frontcache-failed-requests*.log.zip"
)
FILTER_OPTS=()
for pat in "${LOG_PATTERNS[@]}"; do
  FILTER_OPTS+=(--include="$pat")
done
FILTER_OPTS+=(--exclude='*')

mkdir -p "$DEST_DIR" "$STAGE_DIR"

failed_hosts=()

for host in $HOSTS; do
  host_stage="$STAGE_DIR/$host"
  mkdir -p "$host_stage"

  # Reuse one authenticated ssh connection for the preflight check and the
  # transfer, so a password-auth host prompts once per run instead of twice.
  # kept as one plain word-split string: rsync -e splits on whitespace and does
  # not understand shell quoting, so the value must contain no escapes.
  # %C (a hash of user+host+port) keeps the socket path short and unique -- a unix
  # socket path is capped at ~104 bytes, which $TMPDIR on macOS nearly exhausts
  # on its own, so this deliberately does not live under $STAGE_DIR.
  ssh_opts="-o ControlMaster=auto -o ControlPath=$SSH_CTL_DIR/fc-pl-%C -o ControlPersist=60s"
  # shellcheck disable=SC2206  # deliberate word splitting
  ssh_cmd=(ssh $ssh_opts)

  # Preflight: the wrong log directory is the most common cause of an empty pull,
  # so resolve it explicitly here -- either the pinned REMOTE_LOG_DIR or the first
  # candidate that exists -- and say which one was used.
  if [ -n "$REMOTE_LOG_DIR" ]; then
    remote_dir="$REMOTE_LOG_DIR"
    found_dir=0
    if "${ssh_cmd[@]}" "$host" "test -d $remote_dir" 2>&1; then found_dir=1; fi
  else
    # one remote shell loop over the candidates; echoes the first that exists
    remote_dir="$("${ssh_cmd[@]}" "$host" \
      "for d in ${LOG_DIR_CANDIDATES[*]}; do [ -d \$d ] && echo \$d && break; done" 2>/dev/null || true)"
    found_dir=0
    # not a bare `[ ] && ...`: as an else-branch's last command, a false test
    # would be the if-statement's exit status and set -e would abort the run.
    if [ -n "$remote_dir" ]; then found_dir=1; fi
  fi

  if [ "$found_dir" = "0" ]; then
    if [ -n "$REMOTE_LOG_DIR" ]; then
      echo "!!! [$host] remote log dir not found (or ssh failed): $REMOTE_LOG_DIR" >&2
    else
      echo "!!! [$host] no Frontcache log dir found. Tried:" >&2
      printf '      %s\n' "${LOG_DIR_CANDIDATES[@]}" >&2
    fi
    echo "    candidates on this host:" >&2
    "${ssh_cmd[@]}" "$host" \
      'ls -d ~/opt/*/FRONTCACHE_HOME/logs /opt/frontcache*/logs /opt/frontcache*/*/logs 2>/dev/null' >&2 \
      || echo "    (none found -- is Frontcache installed here?)" >&2
    echo "    re-run with: REMOTE_LOG_DIR=<dir> $0 $host" >&2
    failed_hosts+=("$host")
    "${ssh_cmd[@]}" -O exit "$host" 2>/dev/null || true
    continue
  fi

  echo ">>> [$host] pulling logs from $remote_dir ..."

  # current log + rolled .zip archives. Errors are shown, not swallowed.
  rsync_log="$host_stage/.rsync-out"
  # errexit off rather than '|| true': a trailing '|| true' runs as the last simple
  # command and resets PIPESTATUS to (0), hiding the very exit code we need.
  set +e
  rsync -e "ssh $ssh_opts" \
    "${RSYNC_OPTS[@]}" "${FILTER_OPTS[@]}" \
    "$host:$remote_dir/" "$host_stage/" 2>&1 | tee "$rsync_log"
  rc=${PIPESTATUS[0]}
  set -e

  "${ssh_cmd[@]}" -O exit "$host" 2>/dev/null || true

  if [ "$rc" -ne 0 ]; then
    echo "!!! [$host] rsync failed (exit $rc) -- see the output above." >&2
    case "$rc" in
      23|24) echo "    some files were missing or vanished mid-transfer; partial data may still have arrived." >&2 ;;
      12)    echo "    protocol error -- if RSYNC_SUDO=1, sudo on the remote likely demanded a password." >&2 ;;
      127)   echo "    rsync not found on the remote host." >&2 ;;
      255)   echo "    ssh failed (auth or connectivity)." >&2 ;;
    esac
    failed_hosts+=("$host")
    [ "$rc" -eq 23 ] || [ "$rc" -eq 24 ] || continue
  fi

  transferred=$(grep -c '^[<>]f' "$rsync_log" 2>/dev/null || true)
  rm -f "$rsync_log"

  # unzip rolled archives (logback rolls to <name>-<date>.log.zip)
  shopt -s nullglob
  for z in "$host_stage"/*.zip; do
    unzip -o -q "$z" -d "$host_stage"
    rm -f "$z"
  done

  # publish every .log to the drop zone, prefixed with the host alias
  count=0
  for f in "$host_stage"/*.log; do
    base="$(basename "$f")"
    cp -f "$f" "$DEST_DIR/${host}-${base}"
    count=$((count + 1))
  done
  shopt -u nullglob

  if [ "$count" -eq 0 ]; then
    echo "!!! [$host] 0 log files. $remote_dir exists but holds nothing matching:" >&2
    printf '      %s\n' "${LOG_PATTERNS[@]}" >&2
    failed_hosts+=("$host")
  else
    echo ">>> [$host] ${transferred:-0} file(s) transferred, $count log file(s) -> $DEST_DIR"
  fi
done

if [ "${#failed_hosts[@]}" -gt 0 ]; then
  echo ">>> FAILED for: ${failed_hosts[*]}" >&2
  exit 1
fi

echo ">>> Done. Logstash (fc-elk) will pick up new files from $DEST_DIR."
echo "    Open Kibana on :${KIBANA_PORT:-5601} to browse."
echo "    Dashboards:"
echo "      Frontcache Overview  — /app/dashboards#/view/fc-overview"
echo "      Frontcache Errors    — /app/dashboards#/view/fc-errors"
echo "      Frontcache Fallbacks — /app/dashboards#/view/fc-fallbacks"
echo "      Frontcache Rejected Requests — /app/dashboards#/view/fc-rejected"
