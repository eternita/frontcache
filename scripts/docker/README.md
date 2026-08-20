# Frontcache Docker

Container images for Frontcache, plus an ELK stack for browsing its logs.

- **`fc-server/`** — the **Frontcache server** image and the compose project that runs it.
  Frontcache alone (embedded Jetty 12), plain HTTP on 9080, no TLS. Whatever terminates TLS
  goes in front of it: a Kubernetes ingress, an ALB, Cloudflare, or nginx.

  > Through 2.5.1 this image came in two variants — the default was nginx + Frontcache on
  > 80/443, and `-slim` was Frontcache alone. **There is now one image, and it is what `-slim`
  > was.** The nginx front door lives in the public repo's
  > [`examples/front-door`](https://github.com/eternita/frontcache/tree/master/examples/front-door),
  > which has both a two-container compose stack and a script for a VM. See
  > [the plan](../../docs/archive/move-out-nginx-plan.md). 2.6.0 was published under the `-slim` names as
  > well, as a compatibility alias; from 2.7.0 only the plain tag is pushed. Nothing was deleted,
  > so every existing `-slim` tag still resolves.
- **`fc-console/`** — the **Frontcache console** image (realtime stats and cache management,
  port 7080). It is a separate container because it is a separate process: the server's
  launcher deploys exactly one webapp at context `/`.
- **`fc-elk/`** — **Elasticsearch + Kibana + Logstash** (Elastic 8.x) for browsing
  Frontcache request, error, fallback, and rejected-request logs pulled from your FC servers.

The images are built **from the distribution archives** (`./gradlew dist`), not from the
working tree, so an image is reproducible from a published artifact — see
[the distribution proposal](../../docs/archive/frontcache-distribution-proposal.md).

## Requirements

- Docker with the Compose plugin (`docker compose`) and `buildx`
- To **build** images: a JDK to run the Gradle `dist` task on the host (the images ship JDK 25).
  To only **run** published images, Docker is all you need.
- For `pull-logs.sh`: `rsync`, `unzip`, and ssh access to the FC hosts via `~/.ssh/config`

---

## Running the server (and console)

```sh
cd fc-server
cp .env.example .env         # set ORIGIN_HOST / ORIGIN_PATHS / ports
cd ..
./start-fc-server.sh                  # build :local images, start the server
./start-fc-server.sh --with-console   # ... and the console on 127.0.0.1:7080
```

`start-fc-server.sh` builds the archives and the `:local` images, then brings up the compose
project. Then:

- `http://localhost:9080` — plain HTTP. There is no TLS here and nothing on 80/443; that is
  the front door's job, and the front door is not in this repo.
- `docker inspect --format '{{.State.Health.Status}}' fc-server`
- `docker logs -f fc-server`
- `./stop-fc-server.sh` (add `-v` to drop the conf/cache/log volumes)

To run a **published** image instead of building, set `FC_VERSION` to a release in `.env` and
`docker compose pull && docker compose up -d`.

### Configuration

`fc-server/.env` drives the compose project; `.env.example` documents every key. The ones
that matter most:

| Key | Meaning |
| --- | --- |
| `FC_VERSION` | the image tag to run (`local` for images you built; otherwise an exact release) |
| `ORIGIN_HOST` | the backend. **Leave it empty** to let the mounted `frontcache.properties` own `front-cache.origin-host` — the entrypoint then reads the value and never rewrites the file. Set it, and it is authoritative and gets written into the file on every start. |
| `FC_HTTP_PORT` / `FC_BIND` | the published port and bind address. Keep it on `127.0.0.1` when the front door is on the same host |
| `FC_CONF_DIR` | where the config lives. Default is the `fc-conf` named volume; point it at a host directory (`./conf`) to own the config on the host — recommended for production |
| `FC_NODES` / `FC_SITE_KEY` | the nodes the console monitors, and the shared site-key |
| `FC_CONSOLE_BIND` | console bind address, **loopback by default** |

**Config is upgrade-safe.** The image keeps a read-only copy of the shipped config outside
`FRONTCACHE_HOME/conf`, and the entrypoint copies over only the files that are *missing*.
That matters because Docker seeds a named volume from the image **only the first time it is
used** — without this, a newer image's config files would never reach an existing volume, and
upgrades would silently keep running on old config. Files you have edited are never touched.

> **The console has no authentication of its own** and can invalidate cache across the whole
> fleet. It is published on `127.0.0.1` by default; reach it over an ssh tunnel, or put an
> authenticating proxy in front of it. Do not bind it to a public interface.

## Building and publishing images

```sh
./build-push-images.sh                          # version.properties, host arch, loaded locally
./build-push-images.sh 2.5.0 --push             # multi-arch (amd64 + arm64), push 2.5.0 and 2.5
./build-push-images.sh 2.5.0 --push --latest    # ... and :latest
./build-push-images.sh local --only server      # just the two server images
./build-push-images.sh 2.5.0 --push --dry-run   # print the buildx commands and stop
```

Two images are produced:

| Image | Contents | Ports |
| --- | --- | --- |
| `pavlikovskiy/frontcache-server:<tag>` | Frontcache | 9080 |
| `pavlikovskiy/frontcache-console:<tag>` | console | 7080 |

A semver tag also publishes its `X.Y` alias, so `2.5.0` pushes `2.5` as well and a customer
can track patch releases without tracking `:latest`. Customer-facing docs always name the
exact tag.

`--push` implies a **multi-platform** build, which cannot be loaded into the local image
store, so it goes straight to the registry (`docker login` first). It runs on a dedicated
`frontcache` buildx builder, created on demand. Without `--push` the build is
single-platform (the host's) and is loaded locally.

Override the namespace with `NS=` (or a whole name with `IMAGE_SERVER=` / `IMAGE_CONSOLE=`).
The planned move from `pavlikovskiy/*` to `eternita/*` is a
[follow-up](../../docs/archive/frontcache-distribution-followup.md) — a dual-push during the overlap
is just two runs with different `NS`.

### Running a published image without compose

```sh
docker run -d --name fc-server --restart unless-stopped \
  -p 9080:9080 \
  -e ORIGIN_HOST=origin.example.com \
  -v /srv/frontcache/conf:/opt/frontcache-server/FRONTCACHE_HOME/conf \
  -v fc-cache:/opt/frontcache-server/FRONTCACHE_HOME/cache \
  pavlikovskiy/frontcache-server:2.5.0
```

The image carries a `HEALTHCHECK` that GETs `/frontcache-io?action=get-cache-state` locally.
That endpoint answers `200` with an "access denied" JSON body when the request does not
arrive on `front-cache.management.port`, which makes it a liveness probe that needs no site
key: a `200` proves Jetty, the webapp and the engine are up.

Remember `front-cache.http-port` / `front-cache.https-port` in the mounted config: they are
what redirect rewriting uses, so they must be the ports **clients** see (80/443 behind a front
door), not 9080.

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
   This rsyncs `frontcache-requests*.log(.zip)`, `error*.log(.zip)`,
   `fallback*.log(.zip)`, and `frontcache-failed-requests*.log(.zip)` from the FC
   log dir on each host, unzips rolled archives, prefixes each file with the host
   alias, and drops them into `fc-elk/logs/` — which Logstash tails and indexes
   into `frontcache-YYYY.MM.dd`, `frontcache-errors-YYYY.MM.dd`,
   `frontcache-fallbacks-YYYY.MM.dd`, and `frontcache-rejected-YYYY.MM.dd`
   indices.
   (`start-fc-elk.sh` applies index templates first so `geoip.location` is mapped
   as `geo_point` and numerics/enums get proper types.)

2. Open the dashboards:
   - **Overview**: http://localhost:5601/app/dashboards#/view/fc-overview
   - **Errors**: http://localhost:5601/app/dashboards#/view/fc-errors
   - **Fallbacks**: http://localhost:5601/app/dashboards#/view/fc-fallbacks
   - **Rejected Requests**: http://localhost:5601/app/dashboards#/view/fc-rejected

   (or Dashboard → **Frontcache Overview** / **Frontcache Errors** /
   **Frontcache Fallbacks** / **Frontcache Rejected Requests**; explore raw events
   in **Discover**).

`start-fc-elk.sh` imports the data views + all four dashboards automatically on
every start — including after a `stop-fc-elk.sh -v`, which drops the ES volume
where Kibana stores saved objects. If you ever start the stack with raw
`docker compose up` instead, import the `.ndjson` files from `fc-elk/kibana/`
manually via Kibana → Stack Management → Saved Objects → Import.

`./stop-fc-elk.sh` stops the stack. Add `-v` for a full clean slate: it drops the
data volumes (indexed logs **and** the Logstash sincedb read position) *and* empties
the `fc-elk/logs` drop zone — the pulled logs run to gigabytes per host and are
easily the bulk of what the stack costs on disk.

The drop zone is deliberately tied to `-v` rather than cleaned on every stop.
`pull-logs.sh` copies the **whole** remote log each time, and incremental ingest
works only because Logstash remembers a read offset per file; delete the pulled
files while keeping that offset and the next pull re-indexes every line into an
index that already has them — duplicates. Wiping volumes and drop zone together
keeps indices, sincedb, and pulled files consistent.

| Flag | Effect |
| --- | --- |
| *(none)* | stop the containers; keep volumes and pulled logs |
| `-v` | drop data volumes **and** empty `fc-elk/logs` |
| `-v --keep-logs` | drop data volumes, keep the pulled logs (re-ingested on next start) |
| `--logs` | stop and empty `fc-elk/logs` only — frees disk, but the kept sincedb means the next pull re-indexes everything |

Any other flag is passed straight through to `docker compose down`.

### Dashboard: "Frontcache Overview"

- **KPI tiles** — total requests, cache-hit ratio (toplevel), median & p95 latency,
  error rate, bot share.
- **Over time** — request volume by cache status (stacked), cache-hit ratio
  (percentage-stacked), latency percentiles (p50/p90/p95/p99), bandwidth served.
- **Breakdowns** — median latency cache-vs-origin and bot-vs-browser; requests by
  FC node, by domain, and by country; cache-status / cacheable / client-type pies.
- **Top-N tables** — 20 slowest URLs (by median latency) and 20 hottest URLs (with a
  cache-hits column).

### Dashboard: "Frontcache Errors"

- **KPI tiles** — total error count.
- **Over time** — error volume (area chart), error volume by FC node (stacked area).
- **Breakdowns** — errors by logger (top 10), errors by FC node (top 10), log level pie.
- **Top-N** — top exception classes (bar chart).
- **Table** — top error messages with count.

### Dashboard: "Frontcache Fallbacks"

- **KPI tiles** — total fallbacks, fallback file hit ratio, default fallback rate.
- **Over time** — fallback volume by resolution type (stacked area).
- **Breakdowns** — resolution type pie (from file vs default), fallbacks by source
  (top 10), top fallback URLs (top 20 bar chart).
- **Table** — top fallback URLs with total count and "from file" count.

### Dashboard: "Frontcache Rejected Requests"

Fed by `frontcache-failed-requests*.log`: everything a **guard rule**
(`org.frontcache.guard`, see
[the protection design](../../docs/archive/frontcache-protection-tech-design.md)) did before
cache/origin — rejected (400 / 414), redirected (301 / 302), or matched in dry-run —
plus requests that completed through a Hystrix fallback. **`reject_reason` is the
headline dimension: it holds the rule name**, so a new rule appears in every panel
without touching the dashboard.

- **KPI tiles** — total guarded & failed requests, distinct client IPs, guard-action
  share (rule acted vs Hystrix fallback), bot share.
- **Over time** — guard actions & failures by rule (stacked area) and by FC node.
- **Breakdowns** — rule/reason pie and bar (top 10), guard-actions-vs-Hystrix-fallback
  pie, events by FC node, by domain, by country, and by HTTP status sent.
- **Top-N tables** — top 20 client IPs (with per-reason columns and distinct-URL
  count), top 20 URLs, top 10 user agents.
- **Dry-run table** — what a rule *would* have caught, by rule, with distinct client
  IPs and URLs. This is the rollout tool: ship a rule as `dry-run`, watch this panel
  against real traffic, then let it act.

Typical use: a single crawler generating compounding `&amp;`-mangled query strings
shows up as one client IP dominating `bad-request`, which is exactly the pattern the
guard rules were added to shed; a scanner walking the node by IP shows up the same
way under `ip-access`.

### Parsed fields

**Request logs** (`frontcache-requests*.log`) — Logstash grok extracts: `domain`,
`request_method`, `request_type` (`toplevel` / `include` / `include-async`),
`is_cacheable` (`cacheable` / `direct`), `is_cached` (`from-cache` / `dynamic` /
`dynamic-soft`), `hystrix_error` (`success` / `error`), `runtime_millis`,
`length_bytes` (`-1` when unknown), `url`, `clientip`, `server`, `browserBot`
(`bot` / `browser`), `agent`, plus flat `geoip.*` from the client IP (Logstash's
bundled GeoLite2-City). `fc-ping.jsp` health checks are dropped.

**Error logs** (`error*.log`) — each ERROR entry is multi-line (header + exception
stack trace); a Logstash multiline codec joins them so one error = one document
(with the full stack trace in `msg`). Grok extracts: `log_level`, `thread`,
`logger`, `msg`, `server` (from the host-alias filename prefix, feeding the
"by FC node" panels), plus optionally `exception_class` (first class name ending in
Exception/Error/Throwable) and `error_url` (when a URL appears in the message). The
error log line carries only a time (logback `%d{HH:mm:ss.SSS}`), so `@timestamp` is
reconstructed by combining that time with a date: the date parsed from the filename
for rolled archives (e.g. `error-2026-07-18.log`), or the ingest date for the
current day's `error.log` (which has no date in its name).

**Fallback logs** (`fallback*.log`) — Logstash grok extracts: `fallback_source`,
`fallback_status` (`default` / `from file`), `fallback_details`, `fallback_url`.

**Rejected/failed request logs** (`frontcache-failed-requests*.log`) — same columns
as the request log plus a trailing quoted reason, so grok extracts everything listed
above for request logs (incl. `geoip.*`) plus `reject_reason`, `http_status`, and
`failure_type`. The file holds two kinds of lines, which `failure_type` separates:

| `failure_type` | Written by | `is_cached` | `reject_reason` values | `http_status` |
| --- | --- | --- | --- | --- |
| `rejected` | a guard rule's `reject` action → `RequestLogger.logGuardedRequest()` | `rejected` | `bad-request`, `uri-too-long`, or any rule name from `guard-rules.conf` | `400`, `414`, … |
| `redirected` | a guard rule's `redirect` action | `redirected` | rule name, e.g. `ip-access`, `anon-analytics` | `301`, `302`, … |
| `dry-run` | a rule marked `dry-run` — matched and logged, request untouched | `dry-run` | `<rule>:dry-run` | *(dropped — nothing was sent)* |
| `hystrix-fallback` | `RequestLogger.logRequest()` when the request fell back | `from-cache` / `dynamic` / … | `short-circuited`, `timeout`, `rejected`, `failure`, `error` | *(unset)* |

`http_status` is the status Frontcache actually sent, written as an extra unquoted
column after the reason — on guard-action lines only, so the grok matches it as an
optional trailing group. Hystrix-fallback lines end at the reason: the response there
is a normal (usually 200) fallback response, and `logRequest()` deliberately does not
read the servlet response, which is not safe to touch from the include threads it
runs on. It is mapped as a `keyword` — a status code is a category, not a quantity.

Note the filename: `frontcache-failed-requests*.log` does **not** match the request
pipeline's `*frontcache-requests*.log` glob (nor the error pipeline, which excludes
it explicitly), so each log type still lands in exactly one index.

---

## Scripts

Each script ships in two flavours: a Bash version (`*.sh`, macOS/Linux) and a
PowerShell version (`*.ps1`, Windows — PowerShell 6+/`pwsh`). They are behaviourally
equivalent; the table lists the Bash names.

| Script | Purpose |
| --- | --- |
| `start-fc-server.sh` | Build the `:local` images and start the stack (`--with-console`, `--no-build`) |
| `stop-fc-server.sh` | Stop/remove the stack (`-v` drops volumes) |
| `build-push-images.sh "<tag>"` | Build both images from the release archives; `--push` to publish |
| `start-fc-elk.sh` | Start Elasticsearch + Kibana + Logstash |
| `stop-fc-elk.sh` | Stop fc-elk (`-v` drops data volumes **and** empties `fc-elk/logs`; `--logs`, `--keep-logs`) |
| `pull-logs.sh "<hosts>"` | rsync FC request, error, fallback, and rejected-request logs into `fc-elk/logs` |

### Windows (PowerShell)

Run the `.ps1` equivalents from a PowerShell prompt. Env-var overrides use
`$env:NAME="value"` and `--latest` becomes the `-Latest` switch:

```powershell
cd scripts\docker
.\start-fc-server.ps1                       # gradle dist -> image build -> up -d
.\start-fc-server.ps1 -WithConsole          # ... and the console
.\stop-fc-server.ps1 -v                     # stop + drop volumes
.\build-push-images.ps1 2.5.0 -Push -Latest # build both images, push 2.5.0 / 2.5 / latest
.\start-fc-elk.ps1
.\pull-logs.ps1 "fc-edge fc-eu"
.\stop-fc-elk.ps1 -v                        # stop, drop volumes, empty fc-elk\logs
```

Windows is also the answer to "how do I run the server bundle on Windows": the launcher
scripts in the zip are bash-only, so **use the container on Windows** rather than the
archive.

Requirements on Windows: Docker Desktop (with `docker compose` and `buildx`), a JDK on PATH
for the Gradle `dist` task (`gradlew.bat`), and — for `pull-logs.ps1` — `ssh`, `scp`, and
`tar`, all of which ship with Windows 10+ (OpenSSH client + bsdtar). No rsync or
`unzip` is needed. If scripts are blocked by execution policy, launch them with
`powershell -ExecutionPolicy Bypass -File .\start-fc-server.ps1`.

> **Note:** `pull-logs.ps1` uses `ssh` + `scp` + `tar` instead of rsync. On Windows
> the various rsync builds (cwRsync / MSYS2 / Git-Bash) can't drive Windows' OpenSSH
> pipes cleanly and abort with `connection unexpectedly closed (0 bytes received so
> far)`, so the Windows script pulls a tarball over the ssh transport that already
> works for you. It is otherwise behaviourally equivalent to `pull-logs.sh`.

`pull-logs.sh` env knobs: `HOSTS`, `REMOTE_LOG_DIR` (default
`/opt/frontcache/logs`),
`DEST_DIR`, `RSYNC_SUDO` (default `1` — uses `sudo rsync` on the remote; set `0` if
the ssh user can read the logs directly). The script pulls four log types:
`frontcache-requests*.log(.zip)`, `error*.log(.zip)`, `fallback*.log(.zip)`, and
`frontcache-failed-requests*.log(.zip)`.

The PowerShell version (`pull-logs.ps1`) shares `HOSTS`, `REMOTE_LOG_DIR`,
`DEST_DIR`, and `RSYNC_SUDO` (here it controls whether the *remote* `tar`/`chmod`
run under `sudo`). Because it uses ssh/scp rather than rsync, it drops `RSYNC_RSH`
and adds `SSH_CMD` / `SCP_CMD` to force a specific OpenSSH binary:

```powershell
$env:SSH_CMD = "C:\Windows\System32\OpenSSH\ssh.exe"
$env:SCP_CMD = "C:\Windows\System32\OpenSSH\scp.exe"
.\pull-logs.ps1 "fc-edge fc-eu"
```

If a pull fails, first confirm plain `ssh <host>` works from the same shell — that
both trusts the host key and proves key-based auth is set up. Passwordless `sudo`
on the remote is required unless you set `$env:RSYNC_SUDO="0"`.
