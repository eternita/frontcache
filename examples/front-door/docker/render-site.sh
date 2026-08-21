#!/usr/bin/env bash
#
# Startup hook for the nginx container (shape A).
#
# The official nginx image runs every executable /docker-entrypoint.d/*.sh before starting
# nginx, so this needs no custom image and no custom entrypoint: it renders the site config
# into conf.d, makes sure a certificate exists, and returns. nginx then starts normally and
# owns PID 1, which is why there is no watchdog anywhere in this example.
#
# Everything it needs is bind-mounted read-only by docker-compose.yml; the only thing it
# writes is the generated config and (for a demo) the self-signed certificate.
set -euo pipefail

echo ">>> Rendering the Frontcache front-door site config ..."
echo "    upstream: ${FRONTCACHE_HOST:-fc-server}:${FRONTCACHE_HTTP_PORT:-9080}"
echo "    origin:   ${ORIGIN_HOST:-<none - everything goes through Frontcache>}"

# No cert mounted -> generate a self-signed one so :443 comes up at all.
bash /opt/front-door/make-self-signed.sh /etc/nginx/ssl "${TLS_CN:-localhost}"

# NGINX_RESOLVER makes the renderer emit the ORIGIN host through an nginx variable, so it is
# resolved per request instead of once at config-load time. 127.0.0.11 is Docker's embedded
# DNS. The practical effect: an origin that is down or unresolvable cannot stop nginx from
# starting, and cache hits on '/' keep being served.
bash /opt/front-door/render-nginx-site.sh > /etc/nginx/conf.d/default.conf

echo ">>> Rendered /etc/nginx/conf.d/default.conf"
