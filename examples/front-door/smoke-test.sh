#!/usr/bin/env bash
#
# Bring up shape A and assert the things the nginx site template exists to get right.
#
# The front door is easy to get 90% right and quietly wrong in the last 10%: the config will
# load, pages will render, and only one of these four will be broken. Each check below maps to
# a directive in render-nginx-site.sh that is there for a reason:
#
#   1. cache        - the stack is actually wired up: origin -> FC -> nginx, and the second
#                     request is served from cache rather than re-fetched
#   2. hystrix      - `proxy_buffering off` on /hystrix.stream. Without it the SSE stream is
#                     buffered and the console dashboard receives nothing, forever
#   3. gzip         - `gzip_proxied any`. nginx's default is to NOT compress proxied responses,
#                     and Frontcache serves plaintext (it cannot emit brotli), so without this
#                     nothing on the site is compressed
#   4. headers      - `ignore_invalid_headers off`, and the x-frontcache-* headers surviving
#                     the proxy hop intact
#   5. passthrough  - ORIGIN_PATHS reaching the origin directly, never touching the cache
#
#   ./smoke-test.sh                 # uses ports 8080/8443, tears down after
#   KEEP=1 ./smoke-test.sh          # leave the stack running to poke at it
#   FC_IMAGE=... ./smoke-test.sh    # test a specific Frontcache image
set -uo pipefail

SELF_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="fd-smoke"

# Unprivileged ports, so this needs no root and cannot collide with a real front door.
export FD_HTTP_PORT="${FD_HTTP_PORT:-8080}"
export FD_HTTPS_PORT="${FD_HTTPS_PORT:-8443}"
export FC_IMAGE="${FC_IMAGE:-pavlikovskiy/frontcache-server:2.6.0}"
export ORIGIN_SCHEME=http
export ORIGIN_HOST=origin
export ORIGIN_PATHS="/images/ /css/ /js/"

BASE="http://127.0.0.1:$FD_HTTP_PORT"
COMPOSE=(docker compose -p "$PROJECT" -f "$SELF_DIR/docker/docker-compose.yml")

PASS=0; FAIL=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; FAIL=$((FAIL+1)); }

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then
    echo; echo ">>> KEEP=1 - stack left running on $BASE  (stop it with:"
    echo "    docker compose -p $PROJECT -f $SELF_DIR/docker/docker-compose.yml down -v)"
  else
    echo; echo ">>> Tearing down ..."
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1
  fi
}
trap cleanup EXIT

echo ">>> Starting the stack ($FC_IMAGE) ..."
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1
if ! "${COMPOSE[@]}" up -d --wait --wait-timeout 120; then
  echo >&2 "ERROR: stack did not come up"
  "${COMPOSE[@]}" ps
  "${COMPOSE[@]}" logs --tail 40
  exit 1
fi

# The `--wait` above waits on the healthchecks; nginx has none, so give it its moment.
for _ in $(seq 1 30); do
  curl -fsS -o /dev/null "$BASE/" 2>/dev/null && break
  sleep 1
done

echo
echo ">>> Checks"

# ---- 1. the stack serves, and caches ----------------------------------------
# x-frontcache-trace asks Frontcache to report what it did in the response headers. It is also
# check 4: a hyphenated x-frontcache-* request header that survives the nginx hop.
BODY="$(curl -fsS "$BASE/" 2>/dev/null)"
case "$BODY" in
  *"origin home"*) ok "GET / is served through the front door" ;;
  *) bad "GET / did not return the origin page" "got: ${BODY:0:120}" ;;
esac

curl -fsS -o /dev/null -H 'x-frontcache-trace: true' "$BASE/" 2>/dev/null   # warm it
TRACE="$(curl -fsS -D - -o /dev/null -H 'x-frontcache-trace: true' "$BASE/" 2>/dev/null | tr -d '\r')"
if printf '%s' "$TRACE" | grep -qi 'from-cache'; then
  ok "second GET / is served from cache"
else
  bad "no cache hit on the second GET /" "trace headers: $(printf '%s' "$TRACE" | grep -i 'x-frontcache' | head -3)"
fi

# ---- 4. the x-frontcache-* request header reached Frontcache ----------------
if printf '%s' "$TRACE" | grep -qi '^x-frontcache-trace-request'; then
  ok "hyphenated x-frontcache-* request headers survive the proxy hop"
else
  bad "Frontcache did not honour x-frontcache-trace through nginx" \
      "check ignore_invalid_headers / proxy_set_header in the rendered config"
fi

# ---- 3. gzip ----------------------------------------------------------------
ENC="$(curl -fsS -D - -o /dev/null -H 'Accept-Encoding: gzip' "$BASE/" 2>/dev/null | tr -d '\r' | grep -i '^content-encoding:' || true)"
case "$ENC" in
  *gzip*) ok "responses are gzipped for clients that accept it" ;;
  *) bad "no Content-Encoding: gzip" "gzip_proxied any is what makes nginx compress a PROXIED response" ;;
esac

# ---- 2. /hystrix.stream is not buffered -------------------------------------
# An SSE stream never ends, so curl is expected to time out - what matters is whether any
# bytes arrived BEFORE the timeout. Buffered, we would get nothing at all.
STREAM="$(curl -sS --max-time 12 -H 'Accept: text/event-stream' "$BASE/hystrix.stream" 2>/dev/null | head -c 400)"
if [ -n "$STREAM" ]; then
  ok "/hystrix.stream delivers bytes without waiting for the response to end"
else
  bad "/hystrix.stream produced nothing in 12s" "proxy_buffering off is missing, or the stream is not enabled on this build"
fi

# ---- 5. origin passthrough ---------------------------------------------------
IMG="$(curl -fsS "$BASE/images/logo.png" 2>/dev/null)"
case "$IMG" in
  *"straight from the origin"*) ok "ORIGIN_PATHS bypass Frontcache and hit the origin directly" ;;
  *) bad "/images/ did not reach the origin directly" "got: ${IMG:0:120}" ;;
esac

# ---- TLS on 443 --------------------------------------------------------------
if curl -fsSk -o /dev/null "https://127.0.0.1:$FD_HTTPS_PORT/" 2>/dev/null; then
  ok "TLS front door answers on :$FD_HTTPS_PORT (self-signed demo certificate)"
else
  bad "nothing served on https://127.0.0.1:$FD_HTTPS_PORT"
fi

echo
echo ">>> $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
