#!/usr/bin/env bash
#
# Entrypoint for the SLIM Frontcache server image: Frontcache alone, plain HTTP on
# $FRONTCACHE_HTTP_PORT (9080 by default), no nginx and no TLS.
#
# This is the variant for Kubernetes, an ALB, or any deployment that already has an
# ingress terminating TLS - i.e. the multi-region edges of use case #3. For a single host
# with no front door of its own, use the full image instead (nginx on 80/443).
#
# Frontcache runs as PID 1 via exec, so it receives Docker's SIGTERM directly.
set -euo pipefail

SERVER_DIR="/opt/frontcache-server"
DEFAULTS_DIR="/opt/frontcache-defaults/conf"
CONF_DIR="$SERVER_DIR/FRONTCACHE_HOME/conf"
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"

# shellcheck source=seed-conf.sh
. /opt/fc-server/seed-conf.sh

seed_conf "$DEFAULTS_DIR" "$CONF_DIR"
resolve_origin_host "$CONF_DIR/frontcache.properties"

echo ">>> Starting Frontcache (embedded Jetty 12) on :$FRONTCACHE_HTTP_PORT ..."
exec env FRONTCACHE_HTTP_PORT="$FRONTCACHE_HTTP_PORT" "$SERVER_DIR/server/bin/frontcache"
