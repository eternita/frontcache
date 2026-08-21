#!/usr/bin/env bash
#
# Stop the fc-elk stack.
#
#   ./stop-fc-elk.sh              stop the containers, keep everything else
#   ./stop-fc-elk.sh -v           ... and drop the ES/Logstash data volumes
#                                 (indexed logs + sincedb read-position) AND empty
#                                 the logs/ drop zone -- a full clean slate
#   ./stop-fc-elk.sh -v --keep-logs   full volume wipe, but leave the drop zone alone
#   ./stop-fc-elk.sh --logs       stop and empty the drop zone only (see the warning below)
#
# Why the drop zone is tied to -v: pull-logs.sh copies the WHOLE remote log each
# time, and incremental ingest works only because Logstash remembers a read
# offset (sincedb) for each file. Deleting the pulled files without dropping that
# offset means the next pull re-reads every line into an index that already has
# them -- duplicates. Wiping volumes and drop zone together keeps the three bits
# of state (indices, sincedb, pulled files) consistent.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGS_DIR="$SCRIPT_DIR/logs"

DOWN_ARGS=()
WIPE_LOGS=0
KEEP_LOGS=0
DROP_VOLUMES=0
for arg in "$@"; do
  case "$arg" in
    -v|--volumes)  WIPE_LOGS=1; DROP_VOLUMES=1; DOWN_ARGS+=("$arg") ;;
    --logs)        WIPE_LOGS=1 ;;               # not a compose flag -- don't forward
    --keep-logs)   KEEP_LOGS=1 ;;               # ditto
    *)             DOWN_ARGS+=("$arg") ;;       # anything else goes to `docker compose down`
  esac
done
if [ "$KEEP_LOGS" = "1" ]; then WIPE_LOGS=0; fi   # set -e would abort on a bare `[ ] && ...`

( cd "$SCRIPT_DIR" && docker compose down ${DOWN_ARGS+"${DOWN_ARGS[@]}"} )

if [ "$WIPE_LOGS" = "1" ] && [ -d "$LOGS_DIR" ]; then
  # Everything except .gitignore, which is what keeps the (git-ignored) drop zone
  # in the repo. -mindepth/-maxdepth 1 so this can only ever touch the drop zone's
  # own entries.
  before="$(du -sh "$LOGS_DIR" 2>/dev/null | cut -f1 || true)"
  find "$LOGS_DIR" -mindepth 1 -maxdepth 1 ! -name '.gitignore' -exec rm -rf {} +
  echo ">>> logs/ emptied${before:+ (freed ~$before)}."
  if [ "$DROP_VOLUMES" = "0" ]; then
    echo "    NOTE: the sincedb volume was kept, so the next pull-logs.sh writes new"
    echo "    files that Logstash reads from the beginning -- re-indexing lines that"
    echo "    are already in Elasticsearch. Use -v for a consistent reset."
  fi
fi

echo ">>> fc-elk stopped."
