# Frontcache Docker

Two docker-compose projects and a set of control scripts.

- **`fc-server/`** — a single container running **nginx + Frontcache standalone**.
  nginx terminates TLS on 443, serves 80, proxies `/` to the embedded Frontcache
  (Jetty 12, HTTP on 9080) and the configured origin paths to your backend. This is
  the containerized equivalent of `scripts/aws/install-frontcache-server-remote.sh`.
- **`fc-elk/`** — **Elasticsearch + Kibana + Logstash** (Elastic 8.x) for browsing
  Frontcache request logs pulled from your FC servers.

The previous implementation is kept under `scripts/docker-old/` for reference.

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
   This rsyncs `frontcache-requests*.log(.zip)` from `/opt/frontcache/logs` on each
   host, unzips rolled archives, prefixes each file with the host alias, and drops
   them into `fc-elk/logs/` — which Logstash tails and indexes into
   `frontcache-YYYY.MM.dd` indices.

2. **Import the Kibana data view**: Kibana → Stack Management → Saved Objects →
   Import → `fc-elk/kibana/fc-dashboard.ndjson`. This creates the `frontcache-*`
   data view (time field `@timestamp`). Build visualizations/dashboards on top of
   it in Kibana (the old ES 5.x dashboard format can't be imported into 8.x).

3. Browse in **Discover** / **Dashboard** at `http://localhost:5601`.

`./stop-fc-elk.sh` stops the stack (add `-v` to wipe indexed data and the Logstash
read position).

### Parsed fields

Logstash grok extracts: `domain`, `request_method`, `request_type`, `is_cacheable`,
`is_cached`, `hystrix_error`, `runtime_millis`, `length_bytes`, `url`, `clientip`,
`server`, `browserBot`, `agent`, plus `geoip.*` from the client IP (Logstash's
bundled GeoLite2-City). `fc-ping.jsp` health checks are dropped.

---

## Scripts

| Script | Purpose |
| --- | --- |
| `start-fc-server.sh` | Build WAR + image and start the fc-server container |
| `stop-fc-server.sh` | Stop/remove fc-server (`-v` drops volumes) |
| `start-fc-elk.sh` | Start Elasticsearch + Kibana + Logstash |
| `stop-fc-elk.sh` | Stop fc-elk (`-v` drops data volumes) |
| `pull-logs.sh "<hosts>"` | rsync FC request logs into `fc-elk/logs` |

`pull-logs.sh` env knobs: `HOSTS`, `REMOTE_LOG_DIR` (default `/opt/frontcache/logs`),
`DEST_DIR`, `RSYNC_SUDO` (default `1` — uses `sudo rsync` on the remote; set `0` if
the ssh user can read the logs directly).
