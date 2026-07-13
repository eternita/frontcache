#!/usr/bin/env bash
#
# Stop and remove the fc-server container. Pass -v to also drop the cache/log volumes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
( cd "$SCRIPT_DIR/fc-server" && docker compose down "$@" )
echo ">>> fc-server stopped."
