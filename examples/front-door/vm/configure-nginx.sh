#!/usr/bin/env bash
#
# Shape C - put nginx in front of a Frontcache that is already installed on this host.
#
# Run this AFTER install-frontcache.sh, on the same machine, as root. It does what the
# installer's --with-nginx used to do:
#
#     /                        -> Frontcache on 127.0.0.1:<port>  (80, and TLS-terminated 443)
#     /hystrix.stream          -> Frontcache, UNBUFFERED (it is a Server-Sent-Events stream)
#     --origin-paths           -> your origin, bypassing the cache entirely
#
# ...plus the step that is easy to forget once the installer stops doing it: setting
# front-cache.http-port=80 / front-cache.https-port=443, because those are what Frontcache
# uses to rewrite redirects and they must be the CLIENT-FACING ports, not its own.
#
# UBUNTU / DEBIAN ONLY - it installs nginx with apt-get. On RHEL-family hosts the rendering
# is identical; only the package step and the sites-available/sites-enabled layout differ.
#
#   sudo ./configure-nginx.sh --origin-host origin.example.com
#   sudo ./configure-nginx.sh --origin-host origin.example.com \
#        --origin-paths "/images/ /css/ /js/" --tls-cert /etc/ssl/site.crt --tls-key /etc/ssl/site.key
#   sudo ./configure-nginx.sh --origin-host origin.example.com --dry-run
#
# To undo it: rm /etc/nginx/sites-enabled/default && systemctl reload nginx, then set
# front-cache.http-port back to Frontcache's own port.
set -euo pipefail

SELF_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER="${RENDERER:-$SELF_DIR/../render-nginx-site.sh}"
CERT_HELPER="${CERT_HELPER:-$SELF_DIR/../tls/make-self-signed.sh}"

# ---- defaults ----------------------------------------------------------------
FC_HOME="${FC_HOME:-/opt/frontcache/FRONTCACHE_HOME}"   # the installer's symlinked layout
FC_SERVICE="${FC_SERVICE:-frontcache}"
FC_PORT="9080"
ORIGIN_HOST=""
ORIGIN_SCHEME="https"
ORIGIN_PATHS="/images/ /css/ /js/"
TLS_CERT="/etc/nginx/ssl/frontcache.crt"
TLS_KEY="/etc/nginx/ssl/frontcache.key"
TLS_CN="$(hostname -f 2>/dev/null || hostname)"
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --origin-host)   ORIGIN_HOST="${2:?--origin-host needs a value}"; shift ;;
    --origin-scheme) ORIGIN_SCHEME="${2:?--origin-scheme needs http or https}"; shift ;;
    --origin-paths)  ORIGIN_PATHS="${2:?--origin-paths needs a value}"; shift ;;
    --fc-port)       FC_PORT="${2:?--fc-port needs a value}"; shift ;;
    --fc-home)       FC_HOME="${2:?--fc-home needs a path}"; shift ;;
    --tls-cert)      TLS_CERT="${2:?--tls-cert needs a path}"; shift ;;
    --tls-key)       TLS_KEY="${2:?--tls-key needs a path}"; shift ;;
    --dry-run)       DRY_RUN=1 ;;
    -h|--help)       sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)               echo >&2 "unknown option: $1  (try --help)"; exit 1 ;;
  esac
  shift
done

log()  { printf '>>> %s\n' "$*" >&2; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
run()  { if [ "$DRY_RUN" = 1 ]; then printf '    [dry-run] %s\n' "$*" >&2; else "$@"; fi; }

[ "$(id -u)" = 0 ] || die "run this as root (sudo $0 ...)"
[ -f "$RENDERER" ] || die "renderer not found: $RENDERER  (run this from the example checkout, or set RENDERER)"
[ -n "$ORIGIN_HOST" ] || warn "no --origin-host: the origin-path blocks will be omitted and everything will flow through Frontcache on '/'"

# ---- 1. nginx -----------------------------------------------------------------
if command -v nginx >/dev/null 2>&1; then
  log "nginx already installed"
else
  log "Installing nginx ..."
  run apt-get update -y
  run env DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
fi
command -v openssl >/dev/null 2>&1 || run env DEBIAN_FRONTEND=noninteractive apt-get install -y openssl

# ---- 2. TLS -------------------------------------------------------------------
# A real certificate at --tls-cert/--tls-key is used as-is; otherwise a self-signed one is
# generated, which is fine for a first boot and not fine for real traffic.
if [ -s "$TLS_CERT" ] && [ -s "$TLS_KEY" ]; then
  log "Using the TLS certificate at $TLS_CERT"
elif [ "$DRY_RUN" = 1 ]; then
  log "[dry-run] would generate a self-signed certificate at $(dirname "$TLS_CERT")"
else
  [ -f "$CERT_HELPER" ] || die "no certificate at $TLS_CERT and the generator is missing: $CERT_HELPER"
  TLS_CN="$TLS_CN" bash "$CERT_HELPER" "$(dirname "$TLS_CERT")" "$TLS_CN"
fi

# ---- 3. render the site --------------------------------------------------------
# A resolver lets nginx resolve the ORIGIN per request, so an origin DNS blip cannot stop
# nginx from loading or from serving cache hits on '/'. Take the host's own nameservers.
NGINX_RESOLVER="$(awk '/^nameserver/ {printf "%s ", $2} END {print "ipv6=off valid=30s"}' /etc/resolv.conf 2>/dev/null || true)"
case "$NGINX_RESOLVER" in
  "ipv6=off valid=30s") NGINX_RESOLVER=""; warn "no nameserver in /etc/resolv.conf - the origin will be resolved once, at nginx config-load time" ;;
esac

log "Rendering the site config (origin: ${ORIGIN_HOST:-<none>}, upstream: 127.0.0.1:$FC_PORT) ..."
if [ "$DRY_RUN" = 1 ]; then
  FRONTCACHE_HOST=127.0.0.1 FRONTCACHE_HTTP_PORT="$FC_PORT" \
  ORIGIN_HOST="$ORIGIN_HOST" ORIGIN_PATHS="$ORIGIN_PATHS" ORIGIN_SCHEME="$ORIGIN_SCHEME" \
  NGINX_RESOLVER="$NGINX_RESOLVER" TLS_CERT="$TLS_CERT" TLS_KEY="$TLS_KEY" \
    bash "$RENDERER" | sed 's/^/    | /'
else
  FRONTCACHE_HOST=127.0.0.1 FRONTCACHE_HTTP_PORT="$FC_PORT" \
  ORIGIN_HOST="$ORIGIN_HOST" ORIGIN_PATHS="$ORIGIN_PATHS" ORIGIN_SCHEME="$ORIGIN_SCHEME" \
  NGINX_RESOLVER="$NGINX_RESOLVER" TLS_CERT="$TLS_CERT" TLS_KEY="$TLS_KEY" \
    bash "$RENDERER" > /etc/nginx/sites-available/default
  ln -sfn /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default
  nginx -t
fi

# ---- 4. tell Frontcache it is behind a proxy -----------------------------------
# front-cache.http-port / https-port are what redirect rewriting uses, so they must be the
# ports CLIENTS see - 80/443 now that nginx owns them, not Frontcache's own port.
PROPS="$FC_HOME/conf/frontcache.properties"
if [ -f "$PROPS" ]; then
  log "Setting front-cache.http-port=80 / https-port=443 in $PROPS"
  set_prop() {
    local key="$1" val="$2"
    if [ "$DRY_RUN" = 1 ]; then printf '    [dry-run] %s=%s\n' "$key" "$val" >&2; return; fi
    if grep -qE "^[[:space:]]*${key//./\\.}[[:space:]]*=" "$PROPS"; then
      sed -i -E "s|^[[:space:]]*${key//./\\.}[[:space:]]*=.*|${key}=${val}|" "$PROPS"
    else
      printf '\n%s=%s\n' "$key" "$val" >> "$PROPS"
    fi
  }
  set_prop 'front-cache.http-port'  '80'
  set_prop 'front-cache.https-port' '443'
  run systemctl restart "$FC_SERVICE"
else
  warn "$PROPS not found - set front-cache.http-port=80 and front-cache.https-port=443 yourself,"
  warn "or pass --fc-home. Redirects will be rewritten to the wrong port until you do."
fi

# ---- 5. start ------------------------------------------------------------------
run systemctl enable nginx
run systemctl restart nginx

cat >&2 <<REPORT

>>> Front door configured.

    site       /etc/nginx/sites-available/default
    cert       $TLS_CERT
    public     http://<this host>/  and  https://<this host>/
    upstream   http://127.0.0.1:$FC_PORT  (Frontcache, systemd unit '$FC_SERVICE')
    direct     ${ORIGIN_PATHS:-<none>} -> ${ORIGIN_HOST:-<none>}

    Verify:
      nginx -t && systemctl status nginx
      curl -sI http://127.0.0.1/ | head -1
      curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/frontcache-io?action=get-cache-state

    Re-run this script after changing the origin or the paths - it rewrites the whole site.
REPORT
