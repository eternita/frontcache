# Frontcache Docker

Two docker-compose projects and a set of control scripts.

- **`fc-server/`** — a single container running **nginx + Frontcache standalone**.
  nginx terminates TLS on 443, serves 80, proxies `/` to the embedded Frontcache
  (Jetty 12, HTTP on 9080) and the configured origin paths to your backend. This is
  the containerized equivalent of `scripts/bash/install-frontcache-server-remote.sh`.
- **`fc-elk/`** — **Elasticsearch + Kibana + Logstash** (Elastic 8.x) for browsing
  Frontcache request, error, fallback, and rejected-request logs pulled from your
  FC servers.

## Requirements

- Docker with the Compose plugin (`docker compose`)
- For `fc-server`: a JDK to run the Gradle build on the host (the image ships JDK 25)
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
[the protection design](../../docs/frontcache-protection-tech-design.md)) did before
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
| `start-fc-server.sh` | Build WAR + image and start the fc-server container |
| `stop-fc-server.sh` | Stop/remove fc-server (`-v` drops volumes) |
| `build-push-fc-server.sh "<tag>"` | Build WAR + image and push to a registry (Docker Hub) |
| `start-fc-elk.sh` | Start Elasticsearch + Kibana + Logstash |
| `stop-fc-elk.sh` | Stop fc-elk (`-v` drops data volumes) |
| `pull-logs.sh "<hosts>"` | rsync FC request, error, fallback, and rejected-request logs into `fc-elk/logs` |

### Windows (PowerShell)

Run the `.ps1` equivalents from a PowerShell prompt. Env-var overrides use
`$env:NAME="value"` and `--latest` becomes the `-Latest` switch:

```powershell
cd scripts\docker
.\start-fc-server.ps1                      # gradle build -> image build -> up -d
.\stop-fc-server.ps1 -v                    # stop + drop volumes
.\build-push-fc-server.ps1 v1.0 -Latest    # build WAR + image, push :v1.0 and :latest
.\start-fc-elk.ps1
.\pull-logs.ps1 "fc-edge fc-eu"
.\stop-fc-elk.ps1 -v
```

Requirements on Windows: Docker Desktop (with `docker compose`), a JDK on PATH for
the Gradle build (`gradlew.bat`), and — for `pull-logs.ps1` — `ssh`, `scp`, and
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
