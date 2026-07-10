#!/usr/bin/env bash
#
# Build and start the fc-server container (nginx + Frontcache standalone).
#
# 1. builds a fresh WAR + server/lib   (./gradlew :frontcache-server:build)
# 2. builds the fc-server image        (docker compose build)
# 3. starts it                         (docker compose up -d)
#
# Config comes from fc-server/.env (copy fc-server/.env.example first).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/fc-server"

echo ">>> Building fresh WAR (./gradlew :frontcache-server:build) ..."
( cd "$REPO_ROOT" && ./gradlew :frontcache-server:build )

ROOT_WAR="$REPO_ROOT/frontcache-server/server/frontcache-base/webapps/ROOT.war"
if [ ! -f "$ROOT_WAR" ]; then
  echo "ERROR: build did not produce $ROOT_WAR" >&2
  exit 1
fi

echo ">>> Building and starting fc-server ..."
( cd "$COMPOSE_DIR" && docker compose build && docker compose up -d )

echo ">>> fc-server is up."
echo "    http://localhost:${FC_HTTP_PORT:-80}   https://localhost:${FC_HTTPS_PORT:-443} (self-signed)"
echo "    logs: docker logs -f fc-server"
