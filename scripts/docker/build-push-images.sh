#!/usr/bin/env bash
#
# Build (and optionally push) the Frontcache container images.
#
# The images are built FROM the distribution archives produced by `./gradlew dist`, not
# from the working tree, so an image is reproducible from a published artifact. The build
# context is a small staging directory assembled here - the Dockerfile, its entrypoints,
# and the tarball - so nothing else in the repo is sent to the daemon.
#
#   ./build-push-images.sh                          # build the version in version.properties
#                                                   # for the host arch and load it locally
#   ./build-push-images.sh 2.5.0 --push             # multi-arch (amd64 + arm64), push 2.5.0 + 2.5
#   ./build-push-images.sh 2.5.0 --push --latest    # ... and :latest
#   ./build-push-images.sh local --only server      # just the server image, tagged :local
#   ./build-push-images.sh 2.5.0 --push --dry-run   # print the buildx commands and stop
#
# Images built (namespace from NS=, or override a name outright):
#   $NS/frontcache-server:<tag>         Frontcache, plain HTTP on 9080
#   $NS/frontcache-console:<tag>        console, 7080
#
# The server image used to come in two variants - `<tag>` was nginx + Frontcache on 80/443 and
# `<tag>-slim` was Frontcache alone. The nginx front door moved to the public repo under
# examples/front-door, so there is ONE server image and it is what -slim used to be. 2.6.0 was
# published under both names as a compatibility alias; from 2.7.0 only the plain tag is pushed.
# The -slim tags up to 2.6.0 still exist and still resolve - nothing was deleted.
#
# Env knobs:
#   NS              registry namespace (default pavlikovskiy; for the planned move to eternita a
#                   dual-push is just two runs with different NS)
#   IMAGE_SERVER    full server image name, overriding $NS/frontcache-server
#   IMAGE_CONSOLE   console image name, overriding $NS/frontcache-console
#   PLATFORMS       push platforms (default linux/amd64,linux/arm64)
#   NO_BUILD=1      skip ./gradlew dist and use the archives already on disk
#
# --push implies a multi-platform build, which cannot be loaded into the local image store,
# so it goes straight to the registry (a prior `docker login` is required). Without --push
# the build is single-platform (the host's) and is loaded locally, which is what you want
# for start-fc-server.sh and for testing.
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ---- configuration -----------------------------------------------------------
NS="${NS:-pavlikovskiy}"
IMAGE_SERVER="${IMAGE_SERVER:-$NS/frontcache-server}"
IMAGE_CONSOLE="${IMAGE_CONSOLE:-$NS/frontcache-console}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
BUILDER="frontcache"          # buildx builder used for multi-platform pushes

# the bundle version - always from version.properties, independent of the image tag
FC_VERSION="$(sed -n 's/^version=//p' "$REPO_ROOT/version.properties" | tr -d '[:space:]')"
[ -n "$FC_VERSION" ] || { echo >&2 "ERROR: could not read version from version.properties"; exit 1; }

# ---- args --------------------------------------------------------------------
TAG=""
PUSH=0
PUSH_LATEST=0
DRY_RUN=0
ONLY="all"

while [ $# -gt 0 ]; do
  case "$1" in
    --push)    PUSH=1 ;;
    --latest)  PUSH_LATEST=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --only)    ONLY="${2:?--only needs an argument: server|console|all}"; shift ;;
    -h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)        echo >&2 "unknown option: $1"; exit 1 ;;
    *)         [ -z "$TAG" ] || { echo >&2 "unexpected argument: $1"; exit 1; }; TAG="$1" ;;
  esac
  shift
done
TAG="${TAG:-$FC_VERSION}"

case "$ONLY" in
  server|console|all) ;;
  *) echo >&2 "ERROR: --only must be server, console or all"; exit 1 ;;
esac

# ---- tag list ----------------------------------------------------------------
# A semver tag also gets its X.Y alias, so 2.5.0 publishes 2.5 as well and a customer can
# track patch releases without tracking :latest. Customer docs always name the exact tag.
extra_tags() {
  local tag="$1" out=""
  if [[ "$tag" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    out="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
  fi
  if [ "$PUSH_LATEST" = 1 ]; then
    out="${out}${out:+ }latest"
  fi
  echo "$out"
}

# ---- 1. the archives ---------------------------------------------------------
SERVER_TGZ="$REPO_ROOT/frontcache-server/build/distributions/frontcache-server-$FC_VERSION.tar.gz"
CONSOLE_TGZ="$REPO_ROOT/frontcache-console/build/distributions/frontcache-console-$FC_VERSION.tar.gz"

if [ "${NO_BUILD:-0}" = "1" ]; then
  echo ">>> NO_BUILD=1 - using the archives already on disk"
else
  echo ">>> Building the distribution archives (./gradlew dist) ..."
  ( cd "$REPO_ROOT" && ./gradlew dist )
fi

[ "$ONLY" = "console" ] || [ -f "$SERVER_TGZ" ] || { echo >&2 "ERROR: missing $SERVER_TGZ"; exit 1; }
[ "$ONLY" = "server" ]  || [ -f "$CONSOLE_TGZ" ] || { echo >&2 "ERROR: missing $CONSOLE_TGZ"; exit 1; }

# ---- 2. buildx builder -------------------------------------------------------
# The default builder cannot always produce a multi-platform manifest, so pushes go
# through a dedicated docker-container builder. Local single-arch builds use the default.
BUILDX_ARGS=()
if [ "$PUSH" = 1 ]; then
  if ! docker buildx inspect "$BUILDER" > /dev/null 2>&1; then
    echo ">>> Creating the '$BUILDER' buildx builder (docker-container driver) ..."
    [ "$DRY_RUN" = 1 ] || docker buildx create --name "$BUILDER" --driver docker-container > /dev/null
  fi
  BUILDX_ARGS+=(--builder "$BUILDER" --platform "$PLATFORMS" --push)
  echo ">>> Multi-platform build ($PLATFORMS), pushing to the registry"
else
  BUILDX_ARGS+=(--load)
  echo ">>> Single-platform build (host arch), loading into the local image store"
fi

# ---- 3. build ----------------------------------------------------------------
STAGE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/fc-image-ctx.XXXXXX")"
trap 'rm -rf "$STAGE_ROOT"' EXIT

# build_image <staging-name> <dockerfile-dir> <tarball> <archive-name> <image> <primary-tag> <extra-tags> [target]
build_image() {
  local name="$1" srcdir="$2" tarball="$3" archive="$4" image="$5" tag="$6" extras="$7" target="${8:-}"
  local stage="$STAGE_ROOT/$name"

  mkdir -p "$stage"
  cp "$srcdir"/Dockerfile "$srcdir"/*.sh "$stage/"
  # hardlink when possible so a 30-40 MB archive is not copied for every image
  ln "$tarball" "$stage/$archive" 2>/dev/null || cp "$tarball" "$stage/$archive"

  local args=(buildx build "${BUILDX_ARGS[@]}"
              --build-arg "FRONTCACHE_VERSION=$FC_VERSION"
              -f "$stage/Dockerfile" -t "$image:$tag")
  [ -n "$target" ] && args+=(--target "$target")
  local t
  for t in $extras; do args+=(-t "$image:$t"); done
  args+=("$stage")

  echo ">>> $image:$tag${extras:+ (also: $extras)}"
  if [ "$DRY_RUN" = 1 ]; then
    printf '    docker'; printf ' %q' "${args[@]}"; printf '\n'
  else
    docker "${args[@]}"
  fi
}

if [ "$ONLY" = "all" ] || [ "$ONLY" = "server" ]; then
  build_image server "$SCRIPT_DIR/fc-server" "$SERVER_TGZ" frontcache-server.tar.gz \
    "$IMAGE_SERVER" "$TAG" "$(extra_tags "$TAG")"
fi

if [ "$ONLY" = "all" ] || [ "$ONLY" = "console" ]; then
  build_image console "$SCRIPT_DIR/fc-console" "$CONSOLE_TGZ" frontcache-console.tar.gz \
    "$IMAGE_CONSOLE" "$TAG" "$(extra_tags "$TAG")"
fi

# ---- done --------------------------------------------------------------------
echo
if [ "$PUSH" = 1 ]; then
  echo ">>> Pushed. On the target host:"
  echo "    docker run -d --name fc-server --restart unless-stopped \\"
  echo "      -p 9080:9080 -e ORIGIN_HOST=origin.example.com \\"
  echo "      $IMAGE_SERVER:$TAG"
  echo
  echo "    Frontcache serves plain HTTP and no TLS. For an nginx front door on 80/443 see"
  echo "    examples/front-door in the public repo."
else
  echo ">>> Loaded locally. Start the stack with:"
  echo "    FC_VERSION=$TAG ./start-fc-server.sh              # server only"
  echo "    FC_VERSION=$TAG ./start-fc-server.sh --with-console"
fi
