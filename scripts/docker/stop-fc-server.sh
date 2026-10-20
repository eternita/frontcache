#!/usr/bin/env bash
#
# Stop and remove the Frontcache stack. Pass -v to also drop the conf/cache/log volumes.
#
# --profile console is always passed so `down` removes the console too, whether or not it
# was started; without it compose leaves a profiled service running.
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
( cd "$SCRIPT_DIR/fc-server" && docker compose --profile console down "$@" )
echo ">>> Frontcache stack stopped."
