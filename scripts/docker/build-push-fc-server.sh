#!/usr/bin/env bash
#
# Build the fc-server image (nginx + Frontcache standalone) and push it to a
# registry (Docker Hub by default) so it can be pulled and run on a remote host.
#
#   ./build-push-fc-server.sh v1.0
#   ./build-push-fc-server.sh v1.0 --latest          # also tag+push :latest
#   IMAGE=myuser/frontcache ./build-push-fc-server.sh v1.0
#
# Equivalent to:
#   docker build -t pavlikovskiy/frontcache:v1.0 .
#   docker push  pavlikovskiy/frontcache:v1.0
#
# Steps:
#   1. build a fresh WAR + server/lib   (./gradlew :frontcache-server:build)
#   2. docker build the image           (context = repo root, fc-server Dockerfile)
#   3. docker push the tag(s)
#
# On the remote host:
#   docker run -d --name fc-server --restart unless-stopped \
#     -p 80:80 -p 443:443 \
#     -e ORIGIN_HOST=origin.example.com \
#     pavlikovskiy/frontcache:v1.0
#
set -euo pipefail

# ---- configuration (env-overridable) ----------------------------------------
IMAGE="${IMAGE:-pavlikovskiy/frontcache}"     # Docker Hub repo (user/name)
PLATFORM="${PLATFORM:-linux/amd64}"           # remote hosts are usually amd64;
                                              # build host may be arm64 (Apple Silicon)
# -----------------------------------------------------------------------------

TAG="${1:-}"
PUSH_LATEST=0
[ "${2:-}" = "--latest" ] && PUSH_LATEST=1

if [ -z "$TAG" ]; then
  echo "usage: $(basename "$0") <tag> [--latest]" >&2
  echo "  e.g. $(basename "$0") v1.0" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKERFILE="$SCRIPT_DIR/fc-server/Dockerfile"

# ---- 1. build a fresh WAR (Dockerfile COPYs the assembled server tree) -------
echo ">>> Building fresh WAR (./gradlew :frontcache-server:build) ..."
( cd "$REPO_ROOT" && ./gradlew :frontcache-server:build )

ROOT_WAR="$REPO_ROOT/frontcache-server/server/frontcache-base/webapps/ROOT.war"
if [ ! -f "$ROOT_WAR" ]; then
  echo "ERROR: build did not produce $ROOT_WAR" >&2
  exit 1
fi

# ---- 2. build the image (context = repo root) --------------------------------
echo ">>> Building image $IMAGE:$TAG ($PLATFORM) ..."
docker build \
  --platform "$PLATFORM" \
  -f "$DOCKERFILE" \
  -t "$IMAGE:$TAG" \
  "$REPO_ROOT"

if [ "$PUSH_LATEST" = 1 ]; then
  docker tag "$IMAGE:$TAG" "$IMAGE:latest"
fi

# ---- 3. push -----------------------------------------------------------------
# Requires a prior `docker login` for the target registry.
echo ">>> Pushing $IMAGE:$TAG ..."
docker push "$IMAGE:$TAG"
if [ "$PUSH_LATEST" = 1 ]; then
  echo ">>> Pushing $IMAGE:latest ..."
  docker push "$IMAGE:latest"
fi

echo ">>> Done. Pull & run on a remote host:"
echo "    docker run -d --name fc-server --restart unless-stopped \\"
echo "      -p 80:80 -p 443:443 -e ORIGIN_HOST=origin.example.com \\"
echo "      $IMAGE:$TAG"
