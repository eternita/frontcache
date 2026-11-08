#!/usr/bin/env bash
#
# DEVELOPER TOOL - build and run the Community console from THIS working tree, on :7080.
#
# A wrapper around
#   FRONTCACHE_CONFIG=<conf> ./gradlew clean :frontcache-console:appRun
# - the console's exploded webapp and classes on embedded Jetty 12, no plugin - after cleaning the two
# modules it is built from, frontcache-core and frontcache-console.
#
# Usage:
#   scripts/dev/run-console.sh [--no-clean] [<console.conf>]
#
# The config is the argument, else $FRONTCACHE_CONFIG, else the checkout's
# frontcache-console/config/frontcache-console.conf (appRun's default). It is made absolute, since the
# console JVM does not run in the caller's directory.
#
# A Community console ignores the Pro keys (`clusters`, `cache-warmer`, `usage-dir`) and WARNs once at
# startup; `users`, `sso`, `tenants`, `tenants-file` and `audit` need a plugin, and refuse to start.
#
# Example:
#   scripts/dev/run-console.sh ~/opt/hr-fc-console.conf
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"   # this script lives in scripts/dev
PORT=7080   # :frontcache-console:appRun's frontcache.jetty.port

CLEAN=1
CONF="${FRONTCACHE_CONFIG:-}"
for arg in "$@"; do
  case "$arg" in
    -h|--help)  awk 'NR == 1 { next } !/^#/ { exit } { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"; exit 0 ;;
    --no-clean) CLEAN=0 ;;
    -*)         echo "ERROR: unknown option $arg (see --help)" >&2; exit 1 ;;
    *)          CONF="$arg" ;;
  esac
done

if [ -n "$CONF" ]; then
  [ -f "$CONF" ] || { echo "ERROR: no console config at $CONF" >&2; exit 1; }
  export FRONTCACHE_CONFIG="$(cd "$(dirname "$CONF")" && pwd)/$(basename "$CONF")"
  echo "Console config: $FRONTCACHE_CONFIG"
else
  unset FRONTCACHE_CONFIG
  echo "Console config: $REPO_ROOT/frontcache-console/config/frontcache-console.conf (the checkout's default)"
fi

# The Pro console's appRun takes the same port: say which process has it instead of a Jetty bind stack trace.
if pids=$(lsof -ti tcp:$PORT -sTCP:LISTEN 2>/dev/null) && [ -n "$pids" ]; then
  echo "ERROR: port $PORT is in use (pid $(echo $pids | tr '\n' ' ')) - another console appRun?" >&2
  exit 1
fi

TASKS=()
if [ "$CLEAN" = 1 ]; then
  TASKS+=(:frontcache-core:clean :frontcache-console:clean)
fi
TASKS+=(:frontcache-console:appRun)

cd "$REPO_ROOT"
exec ./gradlew "${TASKS[@]}"
