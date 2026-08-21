# Log analytics — Elasticsearch + Kibana + Logstash for Frontcache logs

Frontcache writes four logs under `FRONTCACHE_HOME/logs/`, one line per request or per event:

| File | What it holds |
| --- | --- |
| `frontcache-requests.log` | every request and every `<fc:include>` fragment — cache status, latency, bytes, client, bot flag |
| `error.log` | errors, with stack traces |
| `fallback.log` | every Hystrix fallback that was served, and where it came from |
| `frontcache-failed-requests.log` | guard-rule rejections, redirects and dry-run matches, plus requests that completed through a fallback |

They are readable with `grep`, and unreadable at volume. This example pulls them off your
Frontcache hosts and indexes them into a local Elastic stack, with four ready-made Kibana
dashboards.

```
FC hosts ──pull-logs.sh (rsync/ssh)──▶ logs/ ──Logstash──▶ Elasticsearch ──▶ Kibana :5601
                                    (drop zone)   4 pipelines   4 index sets   4 dashboards
```

Everything runs on **your** machine — three stock Elastic containers, no agent on the
Frontcache hosts, nothing to install next to the product. Security is disabled inside the
stack (see [Hardening](#hardening)); it is a workstation tool.

## Requirements

- Docker with the Compose plugin (`docker compose`)
- ssh access to the Frontcache hosts, ideally as `~/.ssh/config` aliases
- `rsync` and `unzip` for `pull-logs.sh` (the PowerShell version needs neither — see
  [Windows](#windows-powershell))
- ~4 GB of RAM for the stack at the default heaps, plus disk for the pulled logs, which run to
  gigabytes per host

---

## Quick start

```sh
cp .env.example .env         # optional: pin STACK_VERSION, set heap/ports
./start-fc-elk.sh
./pull-logs.sh "fc-us fc-eu" # your ssh aliases (or [user@]hostname)
```

Then open the dashboards:

- **Overview**: http://localhost:5601/app/dashboards#/view/fc-overview
- **Errors**: http://localhost:5601/app/dashboards#/view/fc-errors
- **Fallbacks**: http://localhost:5601/app/dashboards#/view/fc-fallbacks
- **Rejected Requests**: http://localhost:5601/app/dashboards#/view/fc-rejected

(or Dashboard → **Frontcache Overview** / **Errors** / **Fallbacks** / **Rejected Requests**;
raw events are in **Discover**.)

`start-fc-elk.sh` brings up the stack, applies the four Elasticsearch index templates *before*
anything is indexed — so `geoip.location` is a `geo_point` and numerics and enums get real
types rather than being guessed — and imports the data views and dashboards into Kibana. The
import is idempotent (`overwrite=true`), which is what restores the dashboards after a
`stop-fc-elk.sh -v`: that drops the Elasticsearch volume, and Kibana keeps its saved objects
there. If you ever start the stack with a plain `docker compose up`, import the `.ndjson` files
from `kibana/` by hand via Kibana → Stack Management → Saved Objects → Import.

`pull-logs.sh` rsyncs `frontcache-requests*.log(.zip)`, `error*.log(.zip)`, `fallback*.log(.zip)`
and `frontcache-failed-requests*.log(.zip)` from each host, unzips the rolled archives, prefixes
every file with the host alias (so two hosts never collide, and the alias becomes the `server`
field the "by FC node" panels break down on), and drops them into `logs/`. Logstash tails that
directory and indexes into `frontcache-YYYY.MM.dd`, `frontcache-errors-YYYY.MM.dd`,
`frontcache-fallbacks-YYYY.MM.dd` and `frontcache-rejected-YYYY.MM.dd`.

It finds the remote log directory itself, probing (in order)
`/opt/frontcache/FRONTCACHE_HOME/logs` — where the installer script of
[the install guide](../../docs/install-guide.md) puts it —
`~/opt/frontcache-server/FRONTCACHE_HOME/logs`, and
`/opt/frontcache-server/FRONTCACHE_HOME/logs`. Set `REMOTE_LOG_DIR` to pin it instead; a host
that then does not have that directory is an error rather than a fallback.

Re-run `pull-logs.sh` whenever you want fresher data; Logstash remembers how far it read into
each file (sincedb) and ingests only what is new.

## Stopping, and the drop zone

```sh
./stop-fc-elk.sh
```

| Flag | Effect |
| --- | --- |
| *(none)* | stop the containers; keep the data volumes and the pulled logs |
| `-v` | drop the data volumes **and** empty `logs/` — a full clean slate |
| `-v --keep-logs` | drop the data volumes, keep the pulled logs (re-ingested on next start) |
| `--logs` | stop and empty `logs/` only — frees disk, but see the warning below |

Any other flag is passed straight through to `docker compose down`.

Emptying the drop zone is tied to `-v` on purpose. `pull-logs.sh` copies the **whole** remote
log each time, and incremental ingest works only because Logstash remembers a read offset per
file. Delete the pulled files while keeping that offset and the next pull re-indexes every line
into an index that already has them — duplicates. Wiping volumes and drop zone together keeps
indices, sincedb and pulled files consistent.

---

## The dashboards

### Frontcache Overview

- **KPI tiles** — total requests, cache-hit ratio (toplevel), median & p95 latency, error rate,
  bot share.
- **Over time** — request volume by cache status (stacked), cache-hit ratio
  (percentage-stacked), latency percentiles (p50/p90/p95/p99), bandwidth served.
- **Breakdowns** — median latency cache-vs-origin and bot-vs-browser; requests by FC node, by
  domain, and by country; cache-status / cacheable / client-type pies.
- **Top-N tables** — 20 slowest URLs (by median latency) and 20 hottest URLs (with a cache-hits
  column).

### Frontcache Errors

- **KPI tiles** — total error count.
- **Over time** — error volume (area chart), error volume by FC node (stacked area).
- **Breakdowns** — errors by logger (top 10), errors by FC node (top 10), log level pie.
- **Top-N** — top exception classes (bar chart).
- **Table** — top error messages with count.

### Frontcache Fallbacks

- **KPI tiles** — total fallbacks, fallback-file hit ratio, default fallback rate.
- **Over time** — fallback volume by resolution type (stacked area).
- **Breakdowns** — resolution type pie (from file vs default), fallbacks by source (top 10),
  top fallback URLs (top 20 bar chart).
- **Table** — top fallback URLs with total count and "from file" count.

### Frontcache Rejected Requests

Fed by `frontcache-failed-requests*.log`: everything a
[guard rule](../../docs/frontcache-guard-getting-started.md) did before cache or origin —
rejected (400 / 414), redirected (301 / 302), or matched in dry-run — plus requests that
completed through a Hystrix fallback. **`reject_reason` is the headline dimension: it holds the
rule name**, so a new rule appears in every panel without touching the dashboard.

- **KPI tiles** — total guarded & failed requests, distinct client IPs, guard-action share
  (rule acted vs Hystrix fallback), bot share.
- **Over time** — guard actions & failures by rule (stacked area) and by FC node.
- **Breakdowns** — rule/reason pie and bar (top 10), guard-actions-vs-Hystrix-fallback pie,
  events by FC node, by domain, by country, and by HTTP status sent.
- **Top-N tables** — top 20 client IPs (with per-reason columns and distinct-URL count), top 20
  URLs, top 10 user agents.
- **Dry-run table** — what a rule *would* have caught, by rule, with distinct client IPs and
  URLs. This is the rollout tool: ship a rule as `dry-run`, watch this panel against real
  traffic, then let it act.

Typical use: a single crawler generating compounding `&amp;`-mangled query strings shows up as
one client IP dominating `bad-request`, which is exactly the pattern guard rules exist to shed;
a scanner walking the node by IP shows up the same way under `ip-access`.

---

## Parsed fields

**Request logs** (`frontcache-requests*.log`) — grok extracts `domain`, `request_method`,
`request_type` (`toplevel` / `include` / `include-async`), `is_cacheable` (`cacheable` /
`direct`), `is_cached` (`from-cache` / `dynamic` / `dynamic-soft`), `hystrix_error` (`success` /
`error`), `runtime_millis`, `length_bytes` (`-1` when unknown), `url`, `clientip`, `server`,
`browserBot` (`bot` / `browser`), `agent`, plus flat `geoip.*` from the client IP (Logstash's
bundled GeoLite2-City). `fc-ping.jsp` health checks are dropped.

**Error logs** (`error*.log`) — each ERROR entry is multi-line (header + stack trace); a
multiline codec joins them so one error is one document, with the full trace in `msg`. Grok
extracts `log_level`, `thread`, `logger`, `msg`, `server` (from the host-alias filename prefix,
feeding the "by FC node" panels), plus optionally `exception_class` (first class name ending in
Exception/Error/Throwable) and `error_url` (when a URL appears in the message). The error line
carries only a time (`%d{HH:mm:ss.SSS}`), so `@timestamp` is reconstructed by combining it with
a date: the date in the filename for rolled archives (`error-2026-07-18.log`), or the ingest
date for the current day's `error.log`, which has no date in its name.

**Fallback logs** (`fallback*.log`) — `fallback_source`, `fallback_status` (`default` /
`from file`), `fallback_details`, `fallback_url`.

**Rejected/failed request logs** (`frontcache-failed-requests*.log`) — the same columns as the
request log plus a trailing quoted reason, so everything above (including `geoip.*`) plus
`reject_reason`, `http_status` and `failure_type`. The file holds two kinds of line, which
`failure_type` separates:

| `failure_type` | Written by | `is_cached` | `reject_reason` values | `http_status` |
| --- | --- | --- | --- | --- |
| `rejected` | a guard rule's `reject` action | `rejected` | `bad-request`, `uri-too-long`, or any rule name from `guard-rules.conf` | `400`, `414`, … |
| `redirected` | a guard rule's `redirect` action | `redirected` | rule name, e.g. `ip-access`, `anon-analytics` | `301`, `302`, … |
| `dry-run` | a rule marked `dry-run` — matched and logged, request untouched | `dry-run` | `<rule>:dry-run` | *(dropped — nothing was sent)* |
| `hystrix-fallback` | a request that fell back | `from-cache` / `dynamic` / … | `short-circuited`, `timeout`, `rejected`, `failure`, `error` | *(unset)* |

`http_status` is the status Frontcache actually sent, written as an extra unquoted column after
the reason — on guard-action lines only, so grok matches it as an optional trailing group.
Hystrix-fallback lines end at the reason: the response there is a normal (usually 200) fallback
response. It is mapped as a `keyword` — a status code is a category, not a quantity.

Note the filename: `frontcache-failed-requests*.log` does **not** match the request pipeline's
`*frontcache-requests*.log` glob (nor the error pipeline, which excludes it explicitly), so each
log type lands in exactly one index.

---

## What is in this directory

| Path | Role |
| --- | --- |
| `docker-compose.yml` | the three stock Elastic containers; project name pinned to `fc-elk` |
| `.env.example` | `STACK_VERSION`, heaps, published ports |
| `elasticsearch/*.json` | index templates — applied before ingest so field types are right |
| `logstash/config/` | `logstash.yml` and `pipelines.yml` |
| `logstash/pipeline/*.conf` | the four grok pipelines (requests, errors, fallbacks, rejected) |
| `kibana/*.ndjson` | the four dashboards + their data views |
| `logs/` | drop zone `pull-logs.sh` fills and Logstash tails (git-ignored) |

`pipelines.yml` is load-bearing: without it Logstash concatenates every `*.conf` under
`pipeline/` into one pipeline, every input feeds every output, and each event is written to all
four indices.

## Scripts

Each ships in two flavours — Bash (`*.sh`, macOS/Linux) and PowerShell (`*.ps1`, Windows,
PowerShell 6+). They are behaviourally equivalent.

| Script | Purpose |
| --- | --- |
| `start-fc-elk.sh` | start the stack, apply index templates, import dashboards |
| `stop-fc-elk.sh` | stop it (`-v`, `--logs`, `--keep-logs` — see the table above) |
| `pull-logs.sh "<hosts>"` | rsync the four log types from your FC hosts into `logs/` |

`pull-logs.sh` env knobs: `HOSTS`, `REMOTE_LOG_DIR` (pin the remote log directory instead of
probing), `DEST_DIR`, `STAGE_DIR`, `RSYNC_SUDO` (default `0`; set `1` when Frontcache runs under
a different account than the ssh user — it then needs passwordless sudo, since rsync-over-ssh
has no tty to prompt on). `start-fc-elk.sh` and `stop-fc-elk.sh` honour `ES_PORT` and
`KIBANA_PORT`.

### Windows (PowerShell)

```powershell
Copy-Item .env.example .env
.\start-fc-elk.ps1
.\pull-logs.ps1 "fc-us fc-eu"
.\stop-fc-elk.ps1 -v          # stop, drop volumes, empty logs\
```

Requirements: Docker Desktop, plus `ssh`, `scp` and `tar` — all of which ship with Windows 10+
(OpenSSH client + bsdtar). No rsync, no unzip. If execution policy blocks the scripts, run them
as `powershell -ExecutionPolicy Bypass -File .\start-fc-elk.ps1`.

> `pull-logs.ps1` uses ssh + scp + tar rather than rsync on purpose. On Windows the available
> rsync builds (cwRsync / MSYS2 / Git-Bash) cannot drive Windows' OpenSSH pipes cleanly and
> abort with `connection unexpectedly closed (0 bytes received so far)`, so the Windows script
> pulls a tarball over the ssh transport that already works for you.

It shares `HOSTS`, `REMOTE_LOG_DIR`, `DEST_DIR` and `RSYNC_SUDO` (here it controls whether the
*remote* `tar`/`chmod` run under sudo), and adds `SSH_CMD` / `SCP_CMD` to force a specific
OpenSSH binary:

```powershell
$env:SSH_CMD = "C:\Windows\System32\OpenSSH\ssh.exe"
$env:SCP_CMD = "C:\Windows\System32\OpenSSH\scp.exe"
.\pull-logs.ps1 "fc-us fc-eu"
```

## Hardening

`xpack.security` is off, Elasticsearch is published on `9200` and Kibana on `5601` with no
authentication. That is fine on a laptop and wrong anywhere else. If you run this on a shared
host: enable `xpack.security.enabled` on Elasticsearch, wire credentials into Kibana and
Logstash, bind the published ports to `127.0.0.1`, and reach Kibana over an ssh tunnel. The
logs themselves contain client IPs and URLs — treat the stack as holding production data,
because it does.

## Troubleshooting

| Symptom | Look at |
| --- | --- |
| `pull-logs.sh` reports "no Frontcache log dir found" | it printed the paths it probed; pass `REMOTE_LOG_DIR=<dir>` |
| pull works, dashboards stay empty | check the time picker first (pulled logs are usually older than "Last 15 minutes"), then `docker logs fc-logstash` |
| Elasticsearch container exits immediately | almost always memory — lower `ES_HEAP` in `.env`, or give Docker more RAM |
| duplicate events after re-pulling | the drop zone was emptied while the sincedb volume was kept; `./stop-fc-elk.sh -v` and start over |
| dashboards missing after a `-v` | run `./start-fc-elk.sh`, which re-imports them; a bare `docker compose up` does not |
| a map panel is empty | the index template was not applied before ingest, so `geoip.location` is not a `geo_point`; re-run `./start-fc-elk.sh`, then re-index (`-v`) |

---

Install and run Frontcache: [docs/HOWTO-install.md](../../docs/install-guide.md) ·
Guard rules, which feed the Rejected Requests dashboard:
[docs/frontcache-guard-getting-started.md](../../docs/frontcache-guard-getting-started.md)
