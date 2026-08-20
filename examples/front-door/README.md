# Front door — nginx in front of Frontcache

Frontcache speaks plain HTTP on 9080 and does not terminate TLS. In front of real traffic it
needs a front door: something that owns 80/443, terminates TLS, compresses, and decides which
paths go through the cache and which go straight to your app.

This example is that front door, with **nginx**. It is deliberately outside the Frontcache
distribution: the distribution ships Frontcache, and how you front it is your choice — nginx
here, or an ALB, Cloudflare, Traefik, or a Kubernetes ingress, none of which belong inside the
product either.

```
                      ┌── / ───────────▶ Frontcache :9080 ──▶ your app
client ──▶ nginx :80  │                     (cached)
              :443 ───┤
                      └── /images/ ────▶ your app
                          /css/ /js/       (never cached, never touches Frontcache)
```

**Two shapes**, same rendered config:

| | | |
| --- | --- | --- |
| **A** | [`docker/`](docker/) | nginx and Frontcache as two containers. Nothing to build. Start here. |
| **C** | [`vm/`](vm/) | nginx on an Ubuntu host, next to a Frontcache installed by `install-frontcache.sh`. |

There is no one-box (single container running both) shape on purpose — see
[Why there is no combined image](#why-there-is-no-combined-image).

---

## A. Two containers

```sh
cd docker
cp .env.example .env
$EDITOR .env            # ORIGIN_HOST, ORIGIN_PATHS, ports, FC_IMAGE
docker compose up -d
```

Then `http://localhost` and `https://localhost` (self-signed — see [TLS](#tls)).

Out of the box this runs a **stand-in origin** so the stack works with nothing else installed.
To use your own app: set `ORIGIN_HOST` in `.env` and delete the `origin` service from
`docker-compose.yml`.

Both images are stock. The customization is three bind mounts and some env vars:

- [`render-nginx-site.sh`](render-nginx-site.sh) — the site template, mounted read-only.
- [`tls/make-self-signed.sh`](tls/make-self-signed.sh) — a demo certificate, if you have none.
- [`docker/render-site.sh`](docker/render-site.sh) — mounted into `/docker-entrypoint.d/`, which
  the official nginx image runs before starting nginx. It renders the site into `conf.d` and
  returns; nginx then starts normally and is PID 1.

That last point is why there is no watchdog, no supervisor and no signal handling anywhere in
this example: each container runs one process, and Docker restarts them independently.

**Frontcache publishes no host port.** nginx is the only way in. To reach it directly while
debugging:

```sh
docker compose exec nginx curl -si http://fc-server:9080/ | head -20
```

## C. On a VM, next to an installed Frontcache

After `install-frontcache.sh` has put Frontcache on the host as a systemd service:

```sh
sudo ./vm/configure-nginx.sh --origin-host origin.example.com \
     --origin-paths "/images/ /css/ /js/"
```

`--dry-run` prints the config it would write and changes nothing. **Ubuntu/Debian only** — it
installs nginx with `apt-get`. On RHEL-family hosts the rendered config is identical; the
package step and the `sites-available` layout differ.

Besides nginx, it sets **`front-cache.http-port=80`** and **`front-cache.https-port=443`** in
`frontcache.properties`. Those are what Frontcache uses to rewrite redirects, so they have to be
the ports *clients* see — not Frontcache's own. Getting this wrong is subtle: everything works
until something redirects, and then users land on `:9080`.

Re-run the script after changing the origin or the paths; it rewrites the whole site.

---

## What the template actually does

[`render-nginx-site.sh`](render-nginx-site.sh) writes the site config to stdout from env vars.
It is one file, used by both shapes, so there is exactly one place where the front door is
defined. Five things in it are load-bearing, and each is a specific failure if you drop it:

| Directive | Why | If missing |
| --- | --- | --- |
| `proxy_buffering off` on `/hystrix.stream` | it is a Server-Sent-Events stream | the console dashboard receives nothing, forever, with no error |
| `gzip_proxied any` | nginx does **not** compress proxied responses by default, and Frontcache serves plaintext (its HTTP client only decodes gzip, so it cannot emit brotli) | nothing on the site is compressed |
| `ignore_invalid_headers off` | defense in depth for legacy dotted `x.frontcache.*` header names | such headers are silently dropped before Frontcache sees them |
| `X-Forwarded-*` on every proxy pass | Frontcache honours them for redirect rewriting and the management-port check | redirects and the management API misbehave behind the proxy |
| `resolver` + origin host in a **variable** | resolves the origin per request instead of once at config load | an origin DNS blip stops nginx from starting, taking down cache hits that need no origin at all |

Environment variables it reads:

| Variable | Default | Meaning |
| --- | --- | --- |
| `FRONTCACHE_HOST` | `127.0.0.1` | where Frontcache is — a service name in shape A, loopback in shape C |
| `FRONTCACHE_HTTP_PORT` | `9080` | Frontcache's HTTP port |
| `ORIGIN_HOST` | *(empty)* | your app. **Empty omits the origin blocks entirely**, so everything flows through Frontcache and `nginx -t` still passes |
| `ORIGIN_PATHS` | a list of asset paths | space-separated paths proxied straight to the origin, bypassing the cache |
| `ORIGIN_SCHEME` | `https` | `http` only for a local demo origin with no certificate |
| `NGINX_RESOLVER` | *(empty)* | e.g. `127.0.0.11 ipv6=off valid=30s`; see the table above |
| `TLS_CERT` / `TLS_KEY` | `/etc/nginx/ssl/frontcache.{crt,key}` | certificate for the 443 block |

Render it on its own to see what you are about to install:

```sh
FRONTCACHE_HOST=fc-server ORIGIN_HOST=origin.example.com ./render-nginx-site.sh | less
```

## TLS

If `frontcache.crt` and `frontcache.key` are already at the configured path, they are used and
never overwritten — mount or copy your real certificate there and nothing else is needed.

Otherwise [`tls/make-self-signed.sh`](tls/make-self-signed.sh) generates a self-signed pair so
`:443` comes up at all. Browsers will warn; it is for getting started, not for traffic.

> Earlier versions extracted a certificate from the Jetty **demo keystore** inside the
> Frontcache bundle. That reached into a private path of the distribution and hard-coded its
> demo passwords to reuse a certificate that was self-signed anyway. Generating one here is the
> same result with none of the coupling.

## Verify it

```sh
./smoke-test.sh
```

Brings the stack up on ports 8080/8443 against the stand-in origin and asserts the things that
are easy to get quietly wrong: a second request is served **from cache**, `/hystrix.stream`
delivers bytes without waiting for the response to end, responses are gzipped, hyphenated
`x-frontcache-*` request headers survive the proxy hop, `ORIGIN_PATHS` reach the origin
directly, and 443 answers. `KEEP=1 ./smoke-test.sh` leaves it running to poke at.

## Troubleshooting

| Symptom | Look at |
| --- | --- |
| `docker compose up` hangs on the nginx container | it waits for Frontcache to report healthy; `docker compose logs fc-server` |
| nginx exits with `host not found in upstream` | the origin (or `fc-server`) does not resolve. In shape A that means the container is not up; on a VM it means DNS |
| redirects send users to `:9080` | `front-cache.http-port` / `https-port` are not the client-facing ports — see shape C above |
| the console dashboard is empty | `/hystrix.stream` is being buffered somewhere |
| nothing is compressed | `gzip_proxied any` — nginx skips proxied responses without it |

## Why there is no combined image

The Frontcache distribution used to ship one container running nginx *and* Frontcache, with a
watchdog to make two processes behave like one. Shape A deletes that problem instead of moving
it here: two containers, one process each, independent restarts. If you genuinely need a single
container, everything you need is in this directory — but it is not carried here as a supported
shape.

## Version note

`FC_IMAGE` in [`docker/.env.example`](docker/.env.example) pins the **`-slim`** Frontcache image,
which is Frontcache alone with no nginx inside it. Once the distribution's plain
`frontcache-server:<version>` tag becomes that same image, this pin becomes the plain tag.
Pin an exact version either way.
