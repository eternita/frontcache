#!/usr/bin/env bash
#
# Boot test for the distribution archives.
#
# Unpacks frontcache-server-<version>.zip (and optionally the console zip) into a temp
# directory and starts each one exactly the way a customer would - from the unpacked
# launcher, with nothing from this repo on the classpath - then checks that it comes up
# and accepts TCP connections, and shuts it down again.
#
# This is the check `verifyDist` cannot do: verifyDist reads the archive's entry list,
# this proves the bundle actually runs. It needs a JDK 25 and two free ports, which is
# why it is a script driven from the release checklist rather than a build task.
#
#   ./scripts/dist/smoke-test-dist.sh                 # test both bundles
#   ./scripts/dist/smoke-test-dist.sh server          # server only
#   ./scripts/dist/smoke-test-dist.sh console         # console only
#
# Env: FC_SMOKE_PORT (default 19080), FC_CONSOLE_SMOKE_PORT (default 17080),
#      FRONTCACHE_JAVA_HOME (JDK to run with), KEEP_TMP=1 (don't delete the temp dir)
#
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

VERSION="$(sed -n 's/^version=//p' "$REPO_ROOT/version.properties" | tr -d '[:space:]')"
[ -n "$VERSION" ] || { echo >&2 "ERROR: could not read version from version.properties"; exit 1; }

WHAT="${1:-both}"
SERVER_PORT="${FC_SMOKE_PORT:-19080}"
CONSOLE_PORT="${FC_CONSOLE_SMOKE_PORT:-17080}"
STARTUP_TIMEOUT=60

TMP="$(mktemp -d "${TMPDIR:-/tmp}/fc-smoke.XXXXXX")"
cleanup() {
  [ -n "${SERVER_PID:-}" ] && kill "$SERVER_PID" 2>/dev/null || true
  [ -n "${CONSOLE_PID:-}" ] && kill "$CONSOLE_PID" 2>/dev/null || true
  if [ "${KEEP_TMP:-0}" = "1" ]; then
    echo "temp dir kept: $TMP"
  else
    rm -rf "$TMP"
  fi
}
trap cleanup EXIT

fail() { echo >&2 "FAIL: $*"; exit 1; }

# Wait until $1 appears in the log file $2, or the process $3 dies, or we time out.
wait_for_line() {
  local needle="$1" logfile="$2" pid="$3" waited=0
  while [ "$waited" -lt "$STARTUP_TIMEOUT" ]; do
    grep -qF "$needle" "$logfile" 2>/dev/null && return 0
    kill -0 "$pid" 2>/dev/null || { echo "--- log ---"; cat "$logfile"; return 1; }
    sleep 1
    waited=$((waited + 1))
  done
  echo "--- log ---"; cat "$logfile"
  return 1
}

# TCP connect check via bash's /dev/tcp, so we don't depend on curl/nc flavours.
tcp_check() {
  local port="$1"
  (exec 3<>"/dev/tcp/127.0.0.1/$port" && exec 3>&-) 2>/dev/null
}

unpack() {
  local zip="$1"
  [ -f "$zip" ] || fail "archive not found: $zip  (run ./gradlew dist first)"
  echo ">>> unpacking $(basename "$zip")"
  unzip -q "$zip" -d "$TMP"
}

# ---- server ------------------------------------------------------------------
if [ "$WHAT" = "both" ] || [ "$WHAT" = "server" ]; then
  unpack "$REPO_ROOT/frontcache-server/build/distributions/frontcache-server-$VERSION.zip"
  HOME_DIR="$TMP/frontcache-server-$VERSION"

  [ -x "$HOME_DIR/server/bin/frontcache" ] || fail "server/bin/frontcache is not executable in the archive"
  [ -x "$HOME_DIR/bin/frontcache" ]        || fail "bin/frontcache is not executable in the archive"

  echo ">>> starting the standalone server on :$SERVER_PORT"
  FRONTCACHE_HTTP_PORT="$SERVER_PORT" "$HOME_DIR/bin/frontcache" > "$TMP/server.log" 2>&1 &
  SERVER_PID=$!

  wait_for_line "has been started successfully" "$TMP/server.log" "$SERVER_PID" \
    || fail "server did not start within ${STARTUP_TIMEOUT}s"
  tcp_check "$SERVER_PORT" || fail "server is not accepting connections on :$SERVER_PORT"

  # the launcher writes into the UNPACKED FRONTCACHE_HOME, not into the repo
  [ -d "$HOME_DIR/FRONTCACHE_HOME/logs" ] || fail "FRONTCACHE_HOME/logs was not created"

  echo ">>> stopping the server"
  kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  echo "OK: frontcache-server-$VERSION.zip boots from a clean unpack"
fi

# ---- console -----------------------------------------------------------------
if [ "$WHAT" = "both" ] || [ "$WHAT" = "console" ]; then
  unpack "$REPO_ROOT/frontcache-console/build/distributions/frontcache-console-$VERSION.zip"
  CONSOLE_DIR="$TMP/frontcache-console-$VERSION"

  [ -x "$CONSOLE_DIR/bin/frontcache-console" ] || fail "bin/frontcache-console is not executable in the archive"

  echo ">>> starting the console on :$CONSOLE_PORT"
  FRONTCACHE_CONSOLE_PORT="$CONSOLE_PORT" "$CONSOLE_DIR/bin/frontcache-console" > "$TMP/console.log" 2>&1 &
  CONSOLE_PID=$!

  wait_for_line "has been started successfully" "$TMP/console.log" "$CONSOLE_PID" \
    || fail "console did not start within ${STARTUP_TIMEOUT}s"
  tcp_check "$CONSOLE_PORT" || fail "console is not accepting connections on :$CONSOLE_PORT"

  echo ">>> stopping the console"
  kill "$CONSOLE_PID"; wait "$CONSOLE_PID" 2>/dev/null || true
  CONSOLE_PID=""
  echo "OK: frontcache-console-$VERSION.zip boots from a clean unpack"
fi

echo ">>> smoke test passed"
