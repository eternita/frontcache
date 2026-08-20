#!/usr/bin/env bash
#
# Entrypoint for the Frontcache server image: Frontcache alone, plain HTTP on
# $FRONTCACHE_HTTP_PORT (9080 by default), no TLS.
#
# Whatever terminates TLS for you goes in front - an ingress, an ALB, Cloudflare, nginx.
# A worked nginx front door (two containers, or a script for a VM) is in the public repo
# under examples/front-door; it used to be a second image built from this Dockerfile.
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
