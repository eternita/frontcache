# Frontcache — Docker Options

Notes on the four things you can run in containers, and what each is for. Current as of
**2.8.0**.

Everything here uses **published images** — there is nothing to build in this repository.

| Want | Use | Where |
| --- | --- | --- |
| Frontcache alone, plain HTTP on 9080 | `pavlikovskiy/frontcache-server` | [install-guide §D](install-guide.md#d-container-image) |
| The console UI on 7080 | `pavlikovskiy/frontcache-console` | [install-guide §E](install-guide.md#e-console--realtime-stats-and-cache-management) |
| A real front door on 80/443 (TLS, gzip, asset bypass) | nginx + Frontcache, two containers | [examples/front-door/docker](../examples/front-door/docker) |
| Logs in Kibana | three stock Elastic containers | [examples/log-analytics](../examples/log-analytics) |

---

## 1. The server image

```sh
docker run -d --name frontcache --restart unless-stopped \
  -p 9080:9080 \
  -e ORIGIN_HOST=origin.example.com \
  -v /srv/frontcache/conf:/opt/frontcache-server/FRONTCACHE_HOME/conf \
  -v fc-cache:/opt/frontcache-server/FRONTCACHE_HOME/cache \
  pavlikovskiy/frontcache-server:2.8.0
```

Multi-arch (amd64 + arm64), carries a `HEALTHCHECK`.

**The image is Frontcache alone — plain HTTP, no TLS.** That is deliberate and it is what a
Kubernetes ingress, an ALB, or Cloudflare wants behind it. If you need to own 80/443
yourself, that is option 3.

### Two volumes, two different jobs

- `conf/` — **a host bind mount is the production recommendation.** The image keeps its
  defaults *outside* `conf/` and seeds only the files that are **missing**, so a new image can
  add config to an existing volume without touching what you edited.
- `cache/` — pure cache. Drop it whenever you like; a named volume is fine.

`logs/` is worth mounting too if you plan on option 4.

### `ORIGIN_HOST` decides who owns the config

Set it, and it is written into `frontcache.properties` **on every start**. Leave it unset, and
your mounted file is the source of truth and is never rewritten. Pick one; do not set
`ORIGIN_HOST` *and* hand-edit that property.

### With compose

```sh
V=2.8.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V
curl -fLO $BASE/frontcache-server-$V-compose.yml
curl -fL  $BASE/frontcache-server-$V-env.example -o .env
$EDITOR .env                                   # ORIGIN_HOST, ORIGIN_PATHS, ports
docker compose -f frontcache-server-$V-compose.yml up -d
docker compose -f frontcache-server-$V-compose.yml --profile console up -d   # + the console
```

Upgrade: `docker compose pull && docker compose up -d` on a new tag.

## 2. The console image

A **separate container** from the node — it talks to nodes over the management API.

```sh
docker run -d --name frontcache-console --restart unless-stopped \
  -p 127.0.0.1:7080:7080 \
  -e FC_NODES=http://fc-server:9080/ -e FC_SITE_KEY=YOUR_SITE_KEY \
  pavlikovskiy/frontcache-console:2.8.0
```

- `FC_SITE_KEY` must match each node's `front-cache.site-key`.
- **Publish it on loopback, as above.** The console has no authentication of its own and can
  invalidate cache across the whole fleet.
- Console and nodes **upgrade independently, in either order** — they are separate deployments
  and the management API carries both wire shapes.

See [console-dashboards.md](console-dashboards.md) for what is on it.

## 3. Front door — nginx + Frontcache

Frontcache does not terminate TLS. This is the two-container stack that does:

```sh
cd examples/front-door/docker
cp .env.example .env
$EDITOR .env            # ORIGIN_HOST, ORIGIN_PATHS, ports, FC_IMAGE
docker compose up -d
```

```
                      ┌── / ───────────▶ Frontcache :9080 ──▶ your app
client ──▶ nginx :80  │                     (cached)
              :443 ───┤
                      └── /images/ ────▶ your app
                          /css/ /js/       (never cached)
```

- Both images are **stock**; the customization is three bind mounts and some env vars. No
  Dockerfile, nothing to build. `docker/.env.example` pins `FC_IMAGE` at 2.8.0 — set it to
  the release you want.
- It ships a **stand-in origin** so the stack runs with nothing else installed. Point
  `ORIGIN_HOST` at your app and delete the `origin` service.
- **Frontcache publishes no host port** — nginx is the only way in. To poke it while debugging:
  `docker compose exec nginx curl -si http://fc-server:9080/ | head -20`
- One process per container, so no supervisor and no signal handling anywhere in the example.
  nginx renders its site config from `/docker-entrypoint.d/` and is then PID 1.
- `./smoke-test.sh` — from `examples/front-door`, one level up from `docker/` — brings the stack
  up against the stand-in origin and asserts the five things the nginx template exists to get
  right. `KEEP=1` leaves it up; `FC_IMAGE=…` pins a different image.

Frequent gotchas: the nginx container waiting on Frontcache's healthcheck; `host not found in
upstream` meaning the origin is not up; redirects landing on `:9080` because
`front-cache.http-port` / `https-port` are not the *client-facing* ports; nothing compressed
without `gzip_proxied any`; an empty console dashboard because the SSE stream is being buffered
somewhere.

## 4. Log analytics — Elasticsearch + Kibana + Logstash

```sh
cd examples/log-analytics
cp .env.example .env         # optional: pin STACK_VERSION, heap, ports
./start-fc-elk.sh
./pull-logs.sh "fc-us fc-eu"  # ssh aliases
```

- **Three stock Elastic containers on your machine.** Nothing is installed next to Frontcache
  and no agent runs on the nodes — `pull-logs.sh` rsyncs the logs over ssh.
- `start-fc-elk.sh` applies the index templates *before* anything is indexed (so
  `geoip.location` is a real `geo_point` rather than a guess) and imports the dashboards
  idempotently. A plain `docker compose up` skips both — import the `kibana/*.ndjson` by hand.
- Four dashboards at `localhost:5601`: Overview, Errors, Fallbacks, Rejected Requests.
- Budget ~4 GB RAM at default heaps, plus disk for pulled logs (gigabytes per host).
- **Security is disabled inside the stack.** It is a workstation tool, not a production
  deployment. `stop-fc-elk.sh -v` drops the volume.

---
