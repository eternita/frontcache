# Frontcache Docker

Two docker-compose projects and a set of control scripts.

- **`fc-server/`** — a single container running **nginx + Frontcache standalone**.
  nginx terminates TLS on 443, serves 80, proxies `/` to the embedded Frontcache
  (Jetty 12, HTTP on 9080) and the configured origin paths to your backend. This is
  the containerized equivalent of `scripts/aws/install-frontcache-server-remote.sh`.
- **`fc-elk/`** — **Elasticsearch + Kibana + Logstash** (Elastic 8.x) for browsing
  Frontcache request logs pulled from your FC servers.

## Requirements

- Docker with the Compose plugin (`docker compose`)
- For `fc-server`: a JDK to run the Gradle build on the host (the image ships JDK 21)
- For `pull-logs.sh`: `rsync`, `unzip`, and ssh access to the FC hosts via `~/.ssh/config`

---

## fc-server (nginx + Frontcache)

```sh
cd fc-server
cp .env.example .env         # set ORIGIN_HOST / ORIGIN_PATHS
cd ..
./start-fc-server.sh         # gradle build -> image build -> up -d
```

`start-fc-server.sh` runs `./gradlew :frontcache-server:build` to produce a fresh
WAR, builds the image, and starts the container. Then:

- `http://localhost` and `https://localhost` (self-signed cert from the bundled
  Jetty demo keystore — swap in a real one via the `KEYSTORE_*` env vars)
- `docker logs -f fc-server`
- `./stop-fc-server.sh` (add `-v` to drop the cache/log volumes)

**Config** (`fc-server/.env`): `ORIGIN_HOST`, `ORIGIN_PATHS` (paths proxied straight
to the origin instead of being cached), published ports, keystore credentials.

### Publish to a registry (run on a remote host)

To ship the image to Docker Hub so a remote host can `docker pull` and run it
(instead of building on the host), use `build-push-fc-server.sh`:

```sh
docker login                             # once, for the target registry
./build-push-fc-server.sh v1.0           # build WAR + image, push :v1.0
./build-push-fc-server.sh v1.0 --latest  # also tag + push :latest
```

It builds a fresh WAR, builds the image (context = repo root, `linux/amd64` by
default so it runs on amd64 hosts), and pushes. Override the repo/platform with
`IMAGE=` / `PLATFORM=`. Then on the remote host:

```sh
docker run -d --name fc-server --restart unless-stopped \
  -p 80:80 -p 443:443 -e ORIGIN_HOST=origin.example.com \
  pavlikovskiy/frontcache:v1.0
```

---

## fc-elk (Elasticsearch + Kibana + Logstash)

```sh
cd fc-elk
cp .env.example .env         # optional: pin STACK_VERSION, set heap/ports
cd ..
./start-fc-elk.sh
```

Then:

1. **Pull logs** from your FC servers (ssh aliases from `~/.ssh/config`):
   ```sh
   ./pull-logs.sh "fc-edge fc-eu"
   ```
   This rsyncs `frontcache-requests*.log(.zip)` from the FC log dir on each host,
   unzips rolled archives, prefixes each file with the host alias, and drops them
   into `fc-elk/logs/` — which Logstash tails and indexes into `frontcache-YYYY.MM.dd`
   indices. (`start-fc-elk.sh` applies an index template first so `geoip.location`
   is mapped as `geo_point` and numerics/enums get proper types.)

2. Open the dashboard: **http://localhost:5601/app/dashboards#/view/fc-overview**
   (or Dashboard → **Frontcache Overview**; explore raw events in **Discover**).

`start-fc-elk.sh` imports the `frontcache-*` data view + **"Frontcache Overview"**
dashboard (20 panels) automatically on every start — including after a
`stop-fc-elk.sh -v`, which drops the ES volume where Kibana stores saved objects.
If you ever start the stack with raw `docker compose up` instead, import
`fc-elk/kibana/fc-dashboard.ndjson` manually via Kibana → Stack Management → Saved
Objects → Import.

`./stop-fc-elk.sh` stops the stack (add `-v` to wipe indexed data and the Logstash
read position).

### Dashboard: "Frontcache Overview"

- **KPI tiles** — total requests, cache-hit ratio (toplevel), median & p95 latency,
  error rate, bot share.
- **Over time** — request volume by cache status (stacked), cache-hit ratio
  (percentage-stacked), latency percentiles (p50/p90/p95/p99), bandwidth served.
- **Breakdowns** — median latency cache-vs-origin and bot-vs-browser; requests by
  FC node, by domain, and by country; cache-status / cacheable / client-type pies.
- **Top-N tables** — 20 slowest URLs (by median latency) and 20 hottest URLs (with a
  cache-hits column).

### Parsed fields

Logstash grok extracts: `domain`, `request_method`, `request_type` (`toplevel` /
`include` / `include-async`), `is_cacheable` (`cacheable` / `direct`), `is_cached`
(`from-cache` / `dynamic` / `dynamic-soft`), `hystrix_error` (`success` / `error`),
`runtime_millis`, `length_bytes` (`-1` when unknown), `url`, `clientip`, `server`,
`browserBot` (`bot` / `browser`), `agent`, plus flat `geoip.*` from the client IP
(Logstash's bundled GeoLite2-City). `fc-ping.jsp` health checks are dropped.

---

## Scripts

| Script | Purpose |
| --- | --- |
| `start-fc-server.sh` | Build WAR + image and start the fc-server container |
| `stop-fc-server.sh` | Stop/remove fc-server (`-v` drops volumes) |
| `build-push-fc-server.sh "<tag>"` | Build WAR + image and push to a registry (Docker Hub) |
| `start-fc-elk.sh` | Start Elasticsearch + Kibana + Logstash |
| `stop-fc-elk.sh` | Stop fc-elk (`-v` drops data volumes) |
| `pull-logs.sh "<hosts>"` | rsync FC request logs into `fc-elk/logs` |

`pull-logs.sh` env knobs: `HOSTS`, `REMOTE_LOG_DIR` (default
`/opt/frontcache/logs`),
`DEST_DIR`, `RSYNC_SUDO` (default `1` — uses `sudo rsync` on the remote; set `0` if
the ssh user can read the logs directly).
