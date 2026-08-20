#!/usr/bin/env bash
#
# Build the images from this checkout and start the Frontcache stack.
#
# 1. builds the distribution archives + the images, tagged :local
#    (build-push-images.sh -> ./gradlew dist -> docker buildx --load)
# 2. starts the compose project
#
# Config comes from fc-server/.env (copy fc-server/.env.example first).
#
#   ./start-fc-server.sh                  # server only
#   ./start-fc-server.sh --with-console    # server + console (loopback :7080)
#   ./start-fc-server.sh --no-build        # reuse the images already loaded
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/fc-server"

WITH_CONSOLE=0
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --with-console) WITH_CONSOLE=1 ;;
    --no-build)     BUILD=0 ;;
    -h|--help)      sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)              echo >&2 "unknown option: $arg"; exit 1 ;;
  esac
done

# The compose file runs published images by tag, so a local run needs locally-built ones.
# ':local' is what .env.example ships as FC_VERSION.
if [ "$BUILD" = 1 ]; then
  echo ">>> Building the :local images ..."
  "$SCRIPT_DIR/build-push-images.sh" local
else
  echo ">>> --no-build: using the images already in the local store"
fi

COMPOSE_ARGS=()
[ "$WITH_CONSOLE" = 1 ] && COMPOSE_ARGS+=(--profile console)

echo ">>> Starting the stack ..."
# ${A[@]+"${A[@]}"} rather than plain "${A[@]}": expanding an EMPTY array is an unbound
# variable under `set -u` in bash 3.2, which is what macOS ships.
( cd "$COMPOSE_DIR" && docker compose ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} up -d )

# Ask compose for the published port rather than re-reading .env here.
published() { ( cd "$COMPOSE_DIR" && docker compose port "$1" "$2" 2>/dev/null ) || true; }

echo
echo ">>> Frontcache is up."
echo "    http://$(published fc-server "${FRONTCACHE_HTTP_PORT:-9080}")  (plain HTTP - no TLS here;"
echo "     put a front door in front of it, e.g. examples/front-door in the public repo)"
if [ "$WITH_CONSOLE" = 1 ]; then
  echo "    console: http://$(published fc-console 7080)/"
fi
echo "    health:  docker inspect --format '{{.State.Health.Status}}' fc-server"
echo "    logs:    docker logs -f fc-server"
