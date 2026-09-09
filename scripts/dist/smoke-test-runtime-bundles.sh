#!/usr/bin/env bash
#
# Boot test for the per-platform bundles that carry an embedded Java runtime.
#
# The point of those bundles is that they run on a host with NO JDK installed, so that is
# exactly what this tests: it unpacks the bundle somewhere with no java on PATH, starts it,
# and asserts that the launcher chose the BUNDLED runtime rather than anything on the host.
#
#   ./scripts/dist/smoke-test-runtime-bundles.sh                       # every built target
#   ./scripts/dist/smoke-test-runtime-bundles.sh linux-x64             # one target
#   ./scripts/dist/smoke-test-runtime-bundles.sh --only server
#
# A target matching the build host is tested directly. A linux-* target on a non-Linux host
# is tested inside `docker run --platform linux/<arch>` - which is the honest way to do it,
# since a macOS host cannot execute a Linux launcher. macos-* targets can only be tested on
# macOS, and are skipped elsewhere with a clear message.
#
# Companion to smoke-test-dist.sh, which covers the portable (JDK-required) bundles.
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

log()  { printf '>>> %s\n' "$*" >&2; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" > /dev/null 2>&1; }

VERSION="$(sed -n 's/^version=//p' "$REPO_ROOT/version.properties" | tr -d '[:space:]')"
[ -n "$VERSION" ] || die "could not read version from version.properties"

case "$(uname -s)" in Darwin) HOST_OS=macos ;; Linux) HOST_OS=linux ;; *) die "unsupported host" ;; esac
case "$(uname -m)" in arm64|aarch64) HOST_ARCH=aarch64 ;; x86_64|amd64) HOST_ARCH=x64 ;; *) die "unsupported arch" ;; esac
HOST_TARGET="$HOST_OS-$HOST_ARCH"

# ---- one bundle, in place (used both locally and inside the container) --------
# This branch is re-entered via --_inner when running inside Docker.
if [ "${1:-}" = "--_inner" ]; then
  archive="$2"; module="$3"; port="$4"
  workdir="$(mktemp -d)"
  tar -xzf "$archive" -C "$workdir"
  root="$workdir/$module-$VERSION"
  [ -d "$root" ] || { echo "INNER-FAIL: archive did not expand to $module-$VERSION"; exit 1; }
  [ -x "$root/runtime/bin/java" ] || { echo "INNER-FAIL: no executable runtime/bin/java in the bundle"; exit 1; }

  if [ "$module" = "frontcache-server" ]; then
    launcher="$root/bin/frontcache"; portvar="FRONTCACHE_HTTP_PORT"
  else
    launcher="$root/bin/frontcache-console"; portvar="FRONTCACHE_CONSOLE_PORT"
  fi

  # env -i is the crux: no PATH-inherited java, and critically no JAVA_HOME, so if the
  # launcher did not pick the bundled runtime nothing would start at all.
  env -i HOME="$HOME" PATH=/usr/bin:/bin "$portvar=$port" "$launcher" > "$workdir/out.log" 2>&1 &
  pid=$!
  # Kill the JVM on EVERY exit path. Without this a failed assertion exits before the kill
  # below, leaking a running server that holds the port - which then makes the NEXT run of
  # this script fail to bind and report a bogus "process died".
  trap 'kill "$pid" 2>/dev/null || true; rm -rf "$workdir"' EXIT
  waited=0
  while [ "$waited" -lt 90 ]; do
    grep -q "started successfully" "$workdir/out.log" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || { echo "INNER-FAIL: process died"; sed -n '1,40p' "$workdir/out.log"; exit 1; }
    sleep 2; waited=$((waited + 2))
  done
  grep -q "started successfully" "$workdir/out.log" || { echo "INNER-FAIL: did not start in ${waited}s"; sed -n '1,40p' "$workdir/out.log"; exit 1; }

  # the assertion that matters: the JVM in use is the one inside the bundle.
  # Compare against the PHYSICAL path: the launcher resolves its own location with `cd -P`,
  # and on macOS $TMPDIR lives under /var -> /private/var, so the unresolved path never
  # matches what the launcher prints.
  root_resolved="$(cd -P "$root" && pwd)"
  if ! grep -q "java=$root_resolved/runtime/bin/java" "$workdir/out.log"; then
    echo "INNER-FAIL: launcher did not select the bundled runtime"
    grep "java=" "$workdir/out.log" || true
    exit 1
  fi
  (exec 3<>"/dev/tcp/127.0.0.1/$port" && exec 3>&-) 2>/dev/null \
    || { echo "INNER-FAIL: nothing accepting connections on :$port"; exit 1; }

  # The console's realtime page is the only thing in either bundle that uses the JDK's own
  # java.net.http client (DashboardStreamProxyServlet relays an edge's SSE stream through it).
  # A jlink runtime built without that module boots, serves every other screen and accepts
  # connections on the port exactly like a correct one - the failure is a NoClassDefFoundError
  # out of that servlet's static initializer, the first time anyone opens the page. Which is
  # how frontcache-console 2.9.0 shipped with java.net.http missing from JLINK_MODULES.
  #
  # So ask for the relay. "Unknown edge" (403) is the expected answer - "smoke-test" is not one
  # of the console's edges - and any answer at all means the class initialized. A 5xx does not.
  if [ "$module" = "frontcache-console" ]; then
    reply=""
    # admin/admin is what the bundled conf ships; ConsoleAuthFilter answers 401 without it, and
    # a 401 never reaches the servlet, so that is a broken assertion rather than a pass.
    if exec 3<>"/dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      printf 'GET /fc-dashboard-proxy.stream?origin=smoke-test HTTP/1.1\r\nHost: 127.0.0.1:%s\r\nAuthorization: Basic YWRtaW46YWRtaW4=\r\nConnection: close\r\n\r\n' "$port" >&3
      read -t 15 -r reply <&3 || true
      exec 3>&-
    fi
    set -- $reply
    case "${2:-}" in
      5*)  echo "INNER-FAIL: dashboard relay answered '$reply' - runtime is probably missing java.net.http"
           exit 1 ;;
      401) echo "INNER-FAIL: dashboard relay answered 401 - the bundled conf's login is no longer admin/admin,"
           echo "           so this check never reached the servlet. Fix the credentials used here."
           exit 1 ;;
      "")  echo "INNER-FAIL: no reply from the dashboard relay on :$port"; exit 1 ;;
      *)   ;;
    esac
  fi

  # The server bundle's counterpart of the check above, for the same class of defect one module
  # over. Process CPU comes from com.sun.management.OperatingSystemMXBean, which lives in
  # jdk.management - NOT in java.management. A runtime linked without it boots, serves pages,
  # caches, and answers get-node-stats with correct heap, correct uptime and cpuProcessLoad -1.0
  # forever, because the instanceof guarding that bean raises NoClassDefFoundError and FcNodeStats
  # catches it by design. The only symptom is one dash in one column of the console's Edges status,
  # which is how a release shipped with it missing.
  #
  # So ask the node for its own stats and require a real number. The api-key is the one the bundled
  # conf ships; a 401/"NOT_AUTHORIZED" means that default changed and this check stopped testing
  # anything, which is a failure rather than a pass - same reasoning as the 401 branch above.
  if [ "$module" = "frontcache-server" ]; then
    # RETRIED, not asserted once: the bean returns a negative value until it has two samples of its
    # own, so a single reading taken the instant the node finished starting is legitimately -1 on a
    # perfectly good runtime. A broken one never produces anything else, which is what separates
    # them.
    #
    # `read -d ''` takes the WHOLE response in one go - headers and body - bounded by -t so a server
    # that never closes cannot wedge this. Deliberately not a read-per-line loop: the JSON body is
    # the last line and carries no trailing newline, so the read that returns it also reports
    # failure, and a loop using read as its condition drops exactly the line being tested for.
    # (`timeout cat` would be the obvious alternative and is not portable - stock macOS has no
    # timeout, and this same inner branch runs on the build host when the target matches it.)
    stats=""
    tries=0
    while [ "$tries" -lt 10 ]; do
      stats=""
      if exec 3<>"/dev/tcp/127.0.0.1/$port" 2>/dev/null; then
        printf 'GET /frontcache-io?action=get-node-stats HTTP/1.1\r\nHost: 127.0.0.1:%s\r\nAuthorization: Bearer your-key-1234-5678-90000\r\nConnection: close\r\n\r\n' "$port" >&3
        read -t 10 -r -d '' stats <&3 || true
        exec 3>&-
      fi
      case "$stats" in
        *'"cpuProcessLoad":-1'*) ;;             # not settled yet, try again
        *'"cpuProcessLoad"'*)    break ;;       # a real number
        *)                       break ;;       # something else is wrong; reported below
      esac
      tries=$((tries + 1)); sleep 1
    done

    case "$stats" in
      "")                 echo "INNER-FAIL: no reply to get-node-stats on :$port"; exit 1 ;;
      *NOT_AUTHORIZED*|*" 401 "*)
        echo "INNER-FAIL: get-node-stats answered unauthorized - the bundled conf's"
        echo "           front-cache.api-key is no longer your-key-1234-5678-90000, so this check"
        echo "           never reached the action. Fix the key used here."
        exit 1 ;;
      *'"cpuProcessLoad":-1'*)
        echo "INNER-FAIL: the node still reports cpuProcessLoad -1 after ${tries}s - this runtime is"
        echo "           missing jdk.management (or whatever else carries com.sun.management). See"
        echo "           JLINK_MODULES in build-runtime-bundles.sh; heap and uptime look fine either way."
        exit 1 ;;
      *'"cpuProcessLoad"'*) ;;
      *)                  echo "INNER-FAIL: get-node-stats reply carried no cpuProcessLoad: $stats"; exit 1 ;;
    esac
  fi

  echo "INNER-OK: $module-$VERSION on its embedded runtime ($("$root/runtime/bin/java" -version 2>&1 | head -1))"
  exit 0   # the EXIT trap stops the JVM and removes the workdir
fi

# ---- args --------------------------------------------------------------------
TARGETS=""
ONLY="all"
while [ $# -gt 0 ]; do
  case "$1" in
    --only)    ONLY="${2:?--only needs server|console|all}"; shift ;;
    -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)        die "unknown option: $1" ;;
    *)         TARGETS="${TARGETS:+$TARGETS }$1" ;;
  esac
  shift
done

MODULES=""
[ "$ONLY" = "all" ] || [ "$ONLY" = "server" ]  && MODULES="frontcache-server"
[ "$ONLY" = "all" ] || [ "$ONLY" = "console" ] && MODULES="${MODULES:+$MODULES }frontcache-console"

# Default to whatever has actually been built, so this never fails on a target you did not ask
# build-runtime-bundles.sh for.
if [ -z "$TARGETS" ]; then
  TARGETS="$(ls "$REPO_ROOT"/frontcache-server/build/distributions/frontcache-server-"$VERSION"-*.tar.gz 2>/dev/null \
             | sed "s|.*/frontcache-server-$VERSION-||; s|\.tar\.gz$||" | sort -u | tr '\n' ' ')"
  [ -n "$TARGETS" ] || die "no runtime bundles found - run ./scripts/dist/build-runtime-bundles.sh first"
  log "Testing the targets that are built: $TARGETS"
fi

PORT_BASE=19180
FAILED=0
PASSED=0

for target in $TARGETS; do
  for module in $MODULES; do
    archive="$REPO_ROOT/$module/build/distributions/$module-$VERSION-$target.tar.gz"
    if [ ! -f "$archive" ]; then
      warn "skipping $module/$target - $(basename "$archive") not built"
      continue
    fi
    PORT_BASE=$((PORT_BASE + 1))
    log "----- $module / $target -----"

    if [ "$target" = "$HOST_TARGET" ]; then
      if bash "${BASH_SOURCE[0]}" --_inner "$archive" "$module" "$PORT_BASE"; then
        PASSED=$((PASSED + 1))
      else
        FAILED=$((FAILED + 1))
      fi
      continue
    fi

    case "$target" in
      linux-x64|linux-aarch64)
        have docker || { warn "skipping $target - needs Docker to run a Linux launcher from $HOST_TARGET"; continue; }
        plat="linux/amd64"; [ "$target" = "linux-aarch64" ] && plat="linux/arm64"
        if docker run --rm --platform "$plat" \
             -v "$REPO_ROOT/$module/build/distributions:/dist:ro" \
             -v "$SCRIPT_DIR:/scripts:ro" \
             -v "$REPO_ROOT/version.properties:/version.properties:ro" \
             ubuntu:24.04 \
             bash -c "mkdir -p /repo && ln -sf /version.properties /repo/version.properties \
                      && cp -r /scripts /repo/scripts \
                      && bash /repo/scripts/smoke-test-runtime-bundles.sh --_inner \
                           /dist/$module-$VERSION-$target.tar.gz $module $PORT_BASE"; then
          PASSED=$((PASSED + 1))
        else
          FAILED=$((FAILED + 1))
        fi
        ;;
      macos-*)
        warn "skipping $target - a macOS bundle can only be booted on macOS"
        ;;
      *)
        warn "skipping unknown target $target"
        ;;
    esac
  done
done

echo
if [ "$FAILED" -gt 0 ]; then
  die "$FAILED bundle(s) failed, $PASSED passed"
fi
[ "$PASSED" -gt 0 ] || die "nothing was tested"
log "All $PASSED runtime bundle(s) booted on their embedded runtime."
