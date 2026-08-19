#!/usr/bin/env bash
#
# Entrypoint for the Frontcache console image.
#
# The console is a separate process from the server (the server's launcher deploys exactly
# one webapp at context "/"), listening on $FRONTCACHE_CONSOLE_PORT (7080 by default). It
# has NO authentication of its own and can invalidate cache across the whole fleet, so it
# must never be published to a public interface.
#
# Config resolution, in order:
#   1. a frontcache-console.conf mounted over /opt/frontcache-console/conf - used as-is
#   2. FC_NODES (+ FC_SITE_KEY) set  - the file is rendered from them at startup
#   3. neither - the image's placeholder default is seeded and the console starts with no
#      usable nodes, which is enough to prove the container works but not to monitor anything
set -euo pipefail

CONSOLE_DIR="/opt/frontcache-console"
DEFAULTS_DIR="/opt/frontcache-defaults/conf"
CONF_DIR="$CONSOLE_DIR/conf"
CONF_FILE="$CONF_DIR/frontcache-console.conf"
PORT="${FRONTCACHE_CONSOLE_PORT:-7080}"

mkdir -p "$CONF_DIR"

# seed anything missing (fc-logback.xml, and the config placeholder) from image defaults
for src in "$DEFAULTS_DIR"/*; do
  [ -e "$src" ] || continue
  name="$(basename "$src")"
  if [ -e "$CONF_DIR/$name" ]; then
    echo ">>> Keeping existing conf/$name"
  else
    cp -a "$src" "$CONF_DIR/$name"
    echo ">>> Seeded conf/$name from image defaults"
  fi
done

# FC_NODES renders the config. It is only honoured when the operator has NOT mounted their
# own file over the seeded placeholder - detected by the placeholder's example host.
if [ -n "${FC_NODES:-}" ]; then
  if grep -q 'www\.example\.com' "$CONF_FILE" 2>/dev/null; then
    echo ">>> Rendering conf/frontcache-console.conf from FC_NODES ..."
    urls=""
    for node in $(echo "$FC_NODES" | tr ',' ' '); do
      [ -n "$node" ] || continue
      urls="${urls}${urls:+, }\"${node}\""
    done
    cat > "$CONF_FILE" <<CONF
# Managed by the fc-console entrypoint (rendered from FC_NODES / FC_SITE_KEY).
# Mount your own file over /opt/frontcache-console/conf to take ownership of it.
frontcache {
    console {
        urls = [${urls}]
        siteKey = ${FC_SITE_KEY:-default-site-key}
    }
}
CONF
  else
    echo ">>> FC_NODES is set but conf/frontcache-console.conf has been customised - leaving it alone"
  fi
fi

# FrontcacheService reads FRONTCACHE_CONFIG first; the launcher defaults it to conf/.
export FRONTCACHE_CONFIG="${FRONTCACHE_CONFIG:-$CONF_FILE}"

echo ">>> Starting the Frontcache console on :$PORT ..."
exec env FRONTCACHE_CONSOLE_PORT="$PORT" "$CONSOLE_DIR/bin/frontcache-console"
