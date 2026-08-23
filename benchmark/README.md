# benchmark

Replay real frontcache traffic against a frontcache node.

The A/B this harness produced — cache on vs cache off, 100k real requests — is written up in
[docs/value.md](../docs/value.md), with both raw reports in
[`results/`](results).

Two scripts, run in order:

1. **`extract-requests.js`** — turns a frontcache request log into a replayable CSV.
2. **`load-test.js`** — replays that CSV with N parallel workers, reports total runtime, and
   saves every run to `results/<date>_<time>.txt`.

Node 18+ (developed on 24), no dependencies — only node builtins.

## 1. Extract the requests

```bash
node extract-requests.js
```

Reads the request log written by `RequestLogger.logRequest()`
(`frontcache-core/src/main/java/org/frontcache/reqlog/RequestLogger.java` in the Frontcache
source tree) and writes `requests/requests.csv`.

The log has one space separated line per request, with quoted url / client-ip / user-agent:

```
timestamp request-id domain method {success|error} {toplevel|include|include-async} \
{cacheable|direct} {dynamic|from-cache|dynamic-soft} runtime-ms bytes \
"url" "client-ip" frontcache-id {bot|browser} "user-agent"
```

Only **toplevel** lines are kept. Includes (`include` / `include-async`) are resolved by the
engine itself while it serves the toplevel page — replaying them would double-count the work.
`success` and `cacheable` are the other two defaults: `error` lines are hystrix fallbacks, and
`direct` requests bypass the cache entirely.

Duplicates are kept and rows stay in log order, so the CSV carries the real request sequence
and hit distribution. Pass `--unique` if you want one row per distinct url instead.

Output is `url,user_agent`, RFC 4180 quoted. Requests with no `User-Agent` header get `-`.

```
url,user_agent
https://www.hobbyray.com/it/coin-x-y-z-XV4KbzbiAhYAAAFL_Azk0Ldq.htm,"Mozilla/5.0 ... ClaudeBot/1.0 ..."
```

### Options

| flag | meaning |
| --- | --- |
| `--out <file>` | output file (default `requests/requests.csv`) |
| `--limit <n>` | stop after n urls |
| `--unique` | dedupe on url, first occurrence wins |
| `--errors` | also keep hystrix-error lines |
| `--direct` | also keep non-cacheable (`direct`) lines |
| `--browsers-only` | drop bot traffic (most of this log is bots) |
| `--path-only` | write path+query instead of the absolute url |
| `--url-only` | no user-agent column |
| `--no-header` | omit the CSV header row |
| `--stats` | per-column counters to stderr |

The log file is a positional argument; `.gz` is handled. Default:
`../examples/log-analytics/logs/fc-us.hobbyray.com-frontcache-requests.log` — i.e. whatever
[log analytics](../examples/log-analytics) has pulled down via its `pull-logs.sh`.

The whole file is streamed, so log size does not matter — 1.1 GB / 2.5M lines takes ~3s:

```
$ node extract-requests.js --stats
read 2466864 lines, 1511416 toplevel, wrote 681420 requests -> requests/requests.csv
request types: toplevel=1511416 include=917562 include-async=37886
skipped: skippedType=955448 skippedError=5 skippedDirect=829991 duplicates=0
```

## 2. Replay

```bash
node load-test.js --threads 32 --target http://localhost:9080
```

`--threads N` starts N workers that run **in parallel**, each replaying **its own requests
sequentially** — one request in flight per worker, so N is the exact concurrency level. All
workers pull from a single shared cursor over the CSV, so one slow request never leaves part
of the file unreplayed.

These are async workers on the event loop, not `worker_threads`. Replaying HTTP is IO bound;
the generator sustains ~16k req/s against a local stub, well past what a frontcache node under
test will serve.

### Where the requests go

The recorded urls are absolute production urls. By default only path+query is replayed against
`--target`, and the **recorded host is sent as the `Host` header** — that is what frontcache
routes its per-domain config on. So a recorded `https://www.hobbyray.com/en/x.htm` becomes
`GET /en/x.htm` to `localhost:9080` with `Host: www.hobbyray.com`.

Nothing reaches the recorded host unless you pass `--as-is`. Be deliberate with that flag —
this CSV is 681k production requests.

### Options

| flag | meaning |
| --- | --- |
| `--threads <n>`, `-t` | parallel workers (default 10) |
| `--target <url>` | replay against this origin (default `http://localhost:9080`) |
| `--as-is` | use the recorded absolute urls verbatim |
| `--requests <file>` | input CSV (default `requests/requests.csv`) |
| `--limit <n>`, `-n` | at most n requests per pass |
| `--skip <n>` | skip the first n rows |
| `--loop <n>` | replay that set n times (n × `--limit` requests in total) |
| `--timeout <ms>` | per-request timeout (default 30000) |
| `--gzip` | send `accept-encoding: gzip` (default identity, so byte counts are raw) |
| `--no-user-agent` | do not replay the recorded user-agent |
| `--quiet`, `-q` | no live progress line |
| `--csv <file>` | per-request dump: `url,status,ms,bytes,cache` |
| `--results <dir>` | where reports are saved (default `results/`) |
| `--name <label>` | label appended to the file name |
| `--no-save` | print the report only, write no file |

Ctrl-C drains in-flight requests and still prints the report. Exit code is 1 if any request failed.

### Report

Every run is printed and **saved to `results/`**, named after the moment the run started:

```
results/2026-08-22_15-29-20.txt
results/2026-08-22_15-29-34_warm-cache.txt      # with --name "warm cache"
results/2026-08-22_15-29-34_2.txt               # second run within the same second
```

The stamp is local time, `YYYY-MM-DD_HH-MM-SS`, so the directory sorts chronologically. The file
holds exactly what was printed, plus the run's configuration (target, threads, requests file,
limit / loop / skip) so a result stays reproducible without notes. `--no-save` skips the file.

```
----------------------------------------------------------
LOAD TEST RESULTS
----------------------------------------------------------
total runtime     41.16s
started           2026-08-22T20:40:33.297Z
finished          2026-08-22T20:41:14.722Z
threads           64
target            http://localhost:9092
requests file     benchmark/requests/requests.csv
----------------------------------------------------------
requests          681420
  ok              667792
  failed          13628
throughput        16553.6 req/s
transferred       318.48 MB (7.74 MB/s)
----------------------------------------------------------
latency (ms)
  avg             3.9
  min / max       0.1 / 28.0
  p50 / p90       3.7 / 6.5
  p95 / p99       7.0 / 8.1
----------------------------------------------------------
status codes
  200             667792 (98.0%)
  404             13628 (2.0%)
----------------------------------------------------------
frontcache disposition
  from-cache      454280 (66.7%)
  dynamic         227140 (33.3%)
----------------------------------------------------------
saved to          benchmark/results/2026-08-22_20-40-33.txt
```

The **frontcache disposition** block (cache hit ratio) is read from the
`x-frontcache-trace-request.N` response header, which the node only sends when it runs with
`front-cache.log-to-headers=true` in `frontcache.properties`. Without it the block is omitted
and everything else still works.

## Recipes

Cold cache vs warm cache — same 20k requests, three passes:

```bash
node load-test.js -t 32 --limit 20000 --loop 3 --name cold-vs-warm --csv results/warmup-detail.csv
```

Quick smoke test against a node you just started:

```bash
node load-test.js -t 4 -n 200
```

Real-user traffic only, no crawlers:

```bash
node extract-requests.js --browsers-only --out requests/browser.csv
node load-test.js --requests requests/browser.csv -t 16
```

Compare two nodes on the same set:

```bash
node load-test.js -n 50000 --target http://localhost:9080 --name standalone --quiet
node load-test.js -n 50000 --target http://localhost:8080 --name webfilter --quiet
diff results/*_standalone.txt results/*_webfilter.txt
```

## Notes

- `requests/requests.csv` is ~172 MB for the full log. It is generated, not source, and it is a
  dump of real production traffic — `/benchmark/requests/` is in `.gitignore`. Saved reports in
  `results/` are small text files and are worth keeping.
- Both scripts stream their input; memory stays flat regardless of log or CSV size.
- Only GET is replayed (the log's toplevel cacheable traffic is GET).
- Cookies and auth headers are not recorded, so the replay is anonymous traffic — fine for cache
  benchmarking, not for testing logged-in paths.
