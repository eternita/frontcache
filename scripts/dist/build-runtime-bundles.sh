#!/usr/bin/env bash
#
# Build per-platform Frontcache bundles with an EMBEDDED Java runtime, so the customer does
# not have to install a JDK at all.
#
#   ./scripts/dist/build-runtime-bundles.sh                          # all default targets
#   ./scripts/dist/build-runtime-bundles.sh linux-x64                # one target
#   ./scripts/dist/build-runtime-bundles.sh --only server            # skip the console
#   ./scripts/dist/build-runtime-bundles.sh --list-modules           # print the module set
#
# Output, per target, in each module's build/distributions:
#   frontcache-server-<v>-linux-x64.tar.gz   (+ .zip, each with a .sha256)
#
# The archive is the phase-1 bundle plus a `runtime/` directory, with the SAME internal
# directory name - so the installed layout is identical and the installer needs no special
# case beyond skipping the JDK step. The launcher prefers a bundled runtime over $JAVA_HOME.
#
# ---------------------------------------------------------------------------------------
# WHY THIS NEEDS DOCKER FOR NON-NATIVE TARGETS
#
# jlink cannot cross-link, despite what --module-path suggests. Pointing the host's jlink at
# a Linux JDK's jmods produces an image whose CLASSES come from the target but whose
# launcher and native libraries come from the HOST: on macOS you get bin/java as a Mach-O
# binary and lib/*.dylib, which cannot run on Linux. It fails silently - the image builds
# fine and is simply unusable.
#
# So each target's runtime is linked BY that target's own JDK, RUNNING on that platform:
#   * a target matching the host runs directly
#   * a linux-* target runs inside `docker run --platform linux/<arch>`, which is native on a
#     Linux host, native on Apple Silicon for arm64, and emulated otherwise (slower, but
#     jlink is just file processing)
#   * a macos-* target cannot be built from Linux at all, and says so
# ---------------------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

JDK_FEATURE="25"
DEFAULT_TARGETS="linux-x64 linux-aarch64 macos-aarch64"
JDK_CACHE="${JDK_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/frontcache-jdks}"

# ---- the module set ----------------------------------------------------------
# Computed with jdeps over every jar the BUNDLES ship (launcher classpath + WEB-INF/lib -
# both the server's and the console's; they share one module set), then extended by hand for
# the things jdeps structurally CANNOT see: JCE providers and filesystem providers are found
# through ServiceLoader, not through bytecode references, so a purely computed set silently
# omits them.
#
# The scan has to cover the console war as well as the server's. It did not once, and
# java.net.http went missing from the set below: the console started, served every screen and
# read every edge's status over the Apache client, and only the realtime page failed - with a
# NoClassDefFoundError from a static initializer, on a code path no smoke test reached.
#
# This set is a SNAPSHOT of a jdeps run, so it goes stale the moment our own code reaches for a
# module it did not before - and that has now happened twice. jdk.management went missing when
# FcNodeStats landed: process CPU lives on com.sun.management.OperatingSystemMXBean, which is in
# jdk.management and not in java.management, so `instanceof` on it threw NoClassDefFoundError,
# FcNodeStats caught it as designed, and every node on an embedded runtime reported "JVM CPU -"
# for a whole release while heap and uptime were fine. jdeps had always named the module; nobody
# re-ran it. `--verify-modules` below now does, on every build.
#
# java.scripting is deliberately NOT here even though a jdeps scan of the console bundle asks for
# it: it comes from org.springframework.scripting.support, which a console of @Controllers with no
# scripted beans never loads. java.prefs is not here either and does not need to be - java.desktop
# `requires transitive` it, so jlink pulls it in regardless.
#
# Deliberately NOT using --add-modules java.se: that pulls in every Java SE module whether or
# not anything can reach it. The set below is verified the only way that counts - the
# resulting bundle is booted and served from (see smoke-test-dist.sh).
JLINK_MODULES_COMPUTED="\
java.base,\
java.compiler,\
java.desktop,\
java.instrument,\
java.logging,\
java.management,\
java.naming,\
java.net.http,\
java.rmi,\
java.security.jgss,\
java.sql,\
java.xml,\
jdk.incubator.vector,\
jdk.management,\
jdk.unsupported,\
jdk.xml.dom"

# jdk.crypto.ec      SunEC. Without it there is no ECDHE/ECDSA, i.e. no TLS handshake with a
#                    modern https origin. Service-loaded, so invisible to jdeps - this is the
#                    single most important hand-added module.
# java.security.sasl HttpClient's auth schemes.
# jdk.zipfs          Jetty 12 mounts jar/war resources through jar: URIs, which need the zip
#                    filesystem provider. Startup annotation/TLD scanning depends on it.
# jdk.localedata     Locales beyond ROOT/en - date and number formatting outside the US.
# jdk.jfr            Flight Recorder, so a production node can be diagnosed in place.
JLINK_MODULES_ADDED="jdk.crypto.ec,java.security.sasl,jdk.zipfs,jdk.localedata,jdk.jfr"

JLINK_MODULES="$JLINK_MODULES_COMPUTED,$JLINK_MODULES_ADDED"

# --strip-debug is deliberately NOT used. It removes Java line-number tables as well as
# native symbols, which would leave every stack trace a customer sends us without line
# numbers - a far worse trade than the ~10 MB it saves.
JLINK_OPTS="--no-header-files --no-man-pages --compress zip-6"
# -----------------------------------------------------------------------------

log()  { printf '>>> %s\n' "$*" >&2; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" > /dev/null 2>&1; }

# ---- module-set drift check --------------------------------------------------
# Asks jdeps what OUR OWN jars need and fails if JLINK_MODULES does not cover it. Runs before
# any linking, on every build, because the two modules this set has lost so far were both lost
# the same way: our code started using one and nobody re-ran the scan (see the note above).
#
# Scoped to the frontcache jars deliberately, NOT to the whole bundle. A JDK module our code
# names in bytecode is reachable by definition, so a miss there is always a bug. The same claim
# about a third-party jar is a judgement call - Spring asks for java.scripting from classes this
# console never loads - and a check that fails on those gets switched off, which is worse than
# not having one.
#
# Skipped, with a warning, when jdeps is unavailable (a bundle built from a JRE, say). This is a
# guard against our own drift, not a gate on being able to build at all.
verify_modules() {
  have jdeps || { warn "jdeps not found - skipping the JLINK_MODULES drift check"; return 0; }

  local jars=""
  local j
  for j in "$REPO_ROOT"/frontcache-core/build/libs/frontcache-core-"$VERSION".jar \
           "$REPO_ROOT"/frontcache-agent/build/libs/frontcache-agent-"$VERSION".jar \
           "$REPO_ROOT"/frontcache-server/build/libs/frontcache-server-"$VERSION".jar \
           "$REPO_ROOT"/frontcache-console/build/libs/frontcache-console-"$VERSION".jar; do
    [ -f "$j" ] && jars="${jars:+$jars }$j"
  done

  if [ -z "$jars" ]; then
    warn "no frontcache jars built - skipping the JLINK_MODULES drift check (run ./gradlew build first)"
    return 0
  fi

  # --ignore-missing-deps: these jars reference Jetty, Spring and Lucene, none of which is on the
  # path here. Only the JDK modules jdeps resolves are of interest, hence the java./jdk. filter.
  local needed missing
  # shellcheck disable=SC2086
  needed="$(jdeps --multi-release "$JDK_FEATURE" --print-module-deps --ignore-missing-deps $jars 2>/dev/null \
            | tr ',' '\n' | grep -E '^(java|jdk)\.' | sort -u)" || true
  [ -n "$needed" ] || { warn "jdeps produced nothing - skipping the JLINK_MODULES drift check"; return 0; }

  missing="$(comm -23 <(printf '%s\n' "$needed") <(printf '%s\n' "$JLINK_MODULES" | tr ',' '\n' | sort -u) | tr '\n' ' ')"
  missing="${missing% }"

  if [ -n "$missing" ]; then
    die "JLINK_MODULES is missing a module our own code needs: $missing

       jdeps resolved it from the frontcache jars, so it is reachable - add it to
       JLINK_MODULES_COMPUTED in this script. Shipping without it produces a runtime that
       BOOTS and serves: the failure is a NoClassDefFoundError on whichever code path
       touches it, which may be one field of one screen (jdk.management), or one page
       (java.net.http). Both of those shipped."
  fi

  log "JLINK_MODULES covers what our jars need ($(printf '%s\n' "$needed" | tr '\n' ' '))"
}

# ---- args --------------------------------------------------------------------
TARGETS=""
ONLY="all"
VERIFY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only)          ONLY="${2:?--only needs server|console|all}"; shift ;;
    --list-modules)  echo "$JLINK_MODULES" | tr ',' '\n'; exit 0 ;;
    --verify-modules) VERIFY_ONLY=1 ;;
    -h|--help)       sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)              die "unknown option: $1" ;;
    *)               TARGETS="${TARGETS:+$TARGETS }$1" ;;
  esac
  shift
done
TARGETS="${TARGETS:-$DEFAULT_TARGETS}"
case "$ONLY" in server|console|all) ;; *) die "--only must be server, console or all" ;; esac

VERSION="$(sed -n 's/^version=//p' "$REPO_ROOT/version.properties" | tr -d '[:space:]')"
[ -n "$VERSION" ] || die "could not read version from version.properties"

# ---- host platform -----------------------------------------------------------
case "$(uname -s)" in
  Darwin) HOST_OS="macos" ;;
  Linux)  HOST_OS="linux" ;;
  *)      die "unsupported build host: $(uname -s)" ;;
esac
case "$(uname -m)" in
  arm64|aarch64) HOST_ARCH="aarch64" ;;
  x86_64|amd64)  HOST_ARCH="x64" ;;
  *)             die "unsupported build host arch: $(uname -m)" ;;
esac
HOST_TARGET="$HOST_OS-$HOST_ARCH"
log "Build host is $HOST_TARGET; version $VERSION"

verify_modules
[ "$VERIFY_ONLY" = "1" ] && exit 0

# ---- JDK acquisition ---------------------------------------------------------
# Downloads go through the Adoptium assets API rather than the plain binary redirect,
# because that API also reports the sha256 - and a JDK that ends up inside a customer
# artifact should be no less verified than anything else we ship.
jdk_home_for_target() {
  local target="$1"
  local os arch
  case "$target" in
    linux-x64)      os=linux; arch=x64 ;;
    linux-aarch64)  os=linux; arch=aarch64 ;;
    macos-x64)      os=mac;   arch=x64 ;;
    macos-aarch64)  os=mac;   arch=aarch64 ;;
    *)              die "unknown target '$target' (expected linux-x64, linux-aarch64, macos-x64, macos-aarch64)" ;;
  esac

  local dest="$JDK_CACHE/$target"
  if [ -x "$dest/bin/jlink" ]; then
    echo "$dest"
    return 0
  fi

  mkdir -p "$JDK_CACHE"
  local api="https://api.adoptium.net/v3/assets/latest/$JDK_FEATURE/hotspot?architecture=$arch&image_type=jdk&os=$os&vendor=eclipse"
  log "Resolving a JDK $JDK_FEATURE for $target ..."
  local json link sum
  json="$(curl -fsSL "$api")" || die "could not query the Adoptium API for $target"
  # The API pretty-prints, so the pattern must tolerate whitespace around the colon
  # ('"link": "..."'), and the .tar.gz anchor is what skips the macOS .pkg installer entry.
  link="$(printf '%s' "$json" | tr ',' '\n' \
          | grep -o '"link"[[:space:]]*:[[:space:]]*"[^"]*\.tar\.gz"' | head -1 \
          | sed 's/.*"\(https[^"]*\)"$/\1/')"
  [ -n "$link" ] || die "no tar.gz download found for $target"

  # Take the checksum from the release's companion .sha256.txt rather than from the API's
  # own "checksum" field: the JSON carries one checksum per package (the macOS entry lists a
  # .pkg installer first), and pairing a checksum with the right package by scraping is
  # exactly the kind of guess that silently verifies the wrong file. The companion file is
  # tied to this URL by construction.
  sum="$(curl -fsSL "$link.sha256.txt" 2>/dev/null | awk '{print $1; exit}')" || true
  printf '%s' "$sum" | grep -qE '^[0-9a-f]{64}$' \
    || die "no usable sha256 at $link.sha256.txt - refusing to embed an unverified runtime"

  local tgz="$JDK_CACHE/jdk-$target.tar.gz"
  log "Downloading $(basename "$link") ..."
  curl -fsSL -o "$tgz" "$link" || die "JDK download failed: $link"

  local actual
  if have sha256sum; then actual="$(sha256sum "$tgz" | awk '{print $1}')"
  else                    actual="$(shasum -a 256 "$tgz" | awk '{print $1}')"; fi
  [ "$actual" = "$sum" ] || die "JDK checksum MISMATCH for $target
       expected $sum
       actual   $actual"
  log "JDK checksum OK ($target)"

  local unpack="$JDK_CACHE/.unpack-$target"
  rm -rf "$unpack"; mkdir -p "$unpack"
  tar -xzf "$tgz" -C "$unpack"
  # linux: <jdk>/bin ; macOS: <jdk>/Contents/Home/bin
  local home
  home="$(cd "$unpack" && ls -d */Contents/Home 2>/dev/null || true)"
  [ -n "$home" ] || home="$(cd "$unpack" && ls -d */ | head -1)"
  home="$unpack/${home%/}"
  [ -x "$home/bin/jlink" ] || die "unpacked JDK has no bin/jlink: $home"
  rm -rf "$dest"; mkdir -p "$(dirname "$dest")"
  mv "$home" "$dest"
  rm -rf "$unpack" "$tgz"
  echo "$dest"
}

# ---- jlink -------------------------------------------------------------------
# run_jlink <target> <jdk-home> <output-dir>
run_jlink() {
  local target="$1" jdk="$2" out="$3"
  rm -rf "$out"

  if [ "$target" = "$HOST_TARGET" ]; then
    log "Linking the $target runtime natively"
    # shellcheck disable=SC2086
    "$jdk/bin/jlink" --module-path "$jdk/jmods" --add-modules "$JLINK_MODULES" $JLINK_OPTS --output "$out"
    return 0
  fi

  case "$target" in
    linux-*)
      have docker || die "building the $target runtime needs Docker (see the header: jlink cannot cross-link)"
      local plat
      case "$target" in
        linux-x64)     plat="linux/amd64" ;;
        linux-aarch64) plat="linux/arm64" ;;
      esac
      [ "$HOST_ARCH" = "x64" ] && [ "$target" = "linux-aarch64" ] && \
        warn "linux-aarch64 will be built under emulation on this x64 host - slow but correct"
      [ "$HOST_ARCH" = "aarch64" ] && [ "$target" = "linux-x64" ] && \
        warn "linux-x64 will be built under emulation on this arm64 host - slow but correct"
      log "Linking the $target runtime inside a $plat container"
      mkdir -p "$(dirname "$out")"
      docker run --rm --platform "$plat" \
        -v "$jdk:/jdk:ro" -v "$(dirname "$out"):/out" \
        ubuntu:24.04 \
        /jdk/bin/jlink --module-path /jdk/jmods --add-modules "$JLINK_MODULES" \
          $JLINK_OPTS --output "/out/$(basename "$out")"
      ;;
    macos-*)
      die "a $target runtime can only be linked on macOS (jlink cannot cross-link, and there
       is no macOS container). Run this script on a Mac for the macos-* targets."
      ;;
  esac
}

# ---- bundle assembly ---------------------------------------------------------
sha256_file() {
  local file="$1" out="$1.sha256"
  if have sha256sum; then sha256sum "$file" | awk '{print $1"  "FILENAME}' FILENAME="$(basename "$file")" > "$out"
  else shasum -a 256 "$file" | awk '{print $1"  "FILENAME}' FILENAME="$(basename "$file")" > "$out"; fi
}

# build_bundle <module> <target> <runtime-dir>
build_bundle() {
  local module="$1" target="$2" runtime="$3"
  local distdir="$REPO_ROOT/$module/build/distributions"
  local src="$distdir/$module-$VERSION.tar.gz"
  [ -f "$src" ] || die "missing $src - run ./gradlew dist first"

  local work
  work="$(mktemp -d "${TMPDIR:-/tmp}/fc-runtime-bundle.XXXXXX")"
  tar -xzf "$src" -C "$work"
  local inner="$work/$module-$VERSION"
  [ -d "$inner" ] || die "archive did not expand to $module-$VERSION"

  # the runtime rides INSIDE the same top-level directory, so the installed layout - and
  # therefore the installer, the systemd unit and the launcher paths - is unchanged
  cp -R "$runtime" "$inner/runtime"

  local base="$module-$VERSION-$target"
  # --no-xattrs / --no-mac-metadata matter on macOS: without them bsdtar records
  # com.apple.provenance xattrs in the pax headers, and GNU tar on the target host prints
  # "Ignoring unknown extended header keyword" for EVERY file - hundreds of lines of noise
  # that make a perfectly good download look broken. COPYFILE_DISABLE only covers the
  # AppleDouble ._* sidecars, not these. Both flags are probed since GNU tar predates them.
  local tar_opts="--exclude=._* --exclude=.DS_Store"
  tar --no-xattrs --no-mac-metadata -cf /dev/null -T /dev/null 2>/dev/null \
    && tar_opts="$tar_opts --no-xattrs --no-mac-metadata"
  # shellcheck disable=SC2086
  ( cd "$work" && COPYFILE_DISABLE=1 tar $tar_opts \
      -czf "$distdir/$base.tar.gz" "$module-$VERSION" )
  # -X: no extra (macOS) attributes, so the zip is clean on every platform
  ( cd "$work" && rm -f "$distdir/$base.zip" && zip -qr -X "$distdir/$base.zip" "$module-$VERSION" )
  sha256_file "$distdir/$base.tar.gz"
  sha256_file "$distdir/$base.zip"

  local size
  size="$(du -h "$distdir/$base.tar.gz" | awk '{print $1}')"
  log "  $base.tar.gz ($size) + .zip, each with .sha256"
  rm -rf "$work"
}

# ---- main --------------------------------------------------------------------
have curl || die "need curl"
have zip  || die "need zip"

MODULES=""
[ "$ONLY" = "all" ] || [ "$ONLY" = "server" ]  && MODULES="frontcache-server"
[ "$ONLY" = "all" ] || [ "$ONLY" = "console" ] && MODULES="${MODULES:+$MODULES }frontcache-console"

RUNTIME_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/fc-runtimes.XXXXXX")"
trap 'rm -rf "$RUNTIME_ROOT"' EXIT

for target in $TARGETS; do
  log "===== $target ====="
  jdk="$(jdk_home_for_target "$target")"
  runtime="$RUNTIME_ROOT/$target"
  run_jlink "$target" "$jdk" "$runtime"
  [ -x "$runtime/bin/java" ] || die "jlink produced no runtime/bin/java for $target"
  log "runtime: $(du -sh "$runtime" | awk '{print $1}') unpacked"

  for module in $MODULES; do
    build_bundle "$module" "$target" "$runtime"
  done
done

log "Done. Bundles are in <module>/build/distributions/."
log "Boot-test them with: ./scripts/dist/smoke-test-runtime-bundles.sh"
