# Frontcache — Console and Dashboards

Notes on the console, and on the four ways to get Frontcache's numbers into software
you already run. Current as of **2.7.0**.

![Frontcache console](images/fc-console-screen.png "Frontcache console")

---

## 1. The console

A **separate process** from the node, on **7080** (7443 HTTPS). It is not a plugin and it
is not deployed into the server — it talks to one or more nodes over the management API.

```sh
docker run -d --name frontcache-console --restart unless-stopped \
  -p 127.0.0.1:7080:7080 \
  -e FC_NODES=http://fc-server:9080/ -e FC_SITE_KEY=YOUR_SITE_KEY \
  pavlikovskiy/frontcache-console:2.7.0
```

Archive install and the `conf/frontcache-console.conf` form are in
[install-guide §E](install-guide.md#e-console--realtime-stats-and-cache-management).

Two things have to line up or every page is empty:

- `siteKey` must match each node's `front-cache.site-key`.

> **The console has no authentication of its own**, and it can invalidate cache across the
> whole fleet. Loopback, internal network, ssh tunnel, or an authenticating proxy in front.
> Never public.

---

## 2. Prometheus + Grafana — `/fc-metrics`

**Off by default.** One property turns it on:

```properties
front-cache.metrics.export=prometheus        # none | prometheus | otlp | both
```

A node then serves the Prometheus text format at `/fc-metrics`.

```
frontcache_command_events_total{command="Cache-Hits",event="success"}        3.0
frontcache_command_latency_seconds{command="Cache-Origin-http",quantile="99"} 0.031
frontcache_threadpool_rejections_total{pool="OriginHitsPool"}                 0.0
frontcache_resilience4j_circuitbreaker_state{name="Cache-Origin-http",state="closed"} 1.0

frontcache_cache_reads_total{result="hit",tier="L1"}      24.0
frontcache_cache_reads_total{result="hit",tier="L2"}     187.0
frontcache_cache_reads_total{result="miss",tier="none"}   41.0
frontcache_cache_entries{tier="L2"}                    4021.0
frontcache_cache_disk_bytes{tier="L2"}             15463219.0
frontcache_cache_evictions_total{tier="L1"}             112.0
```

Hit ratio, and the follow-up question:

```promql
# overall hit ratio
sum(rate(frontcache_cache_reads_total{result="hit"}[5m]))
  / sum(rate(frontcache_cache_reads_total[5m]))

# ... served off disk, i.e. L1 too small for the working set
sum(rate(frontcache_cache_reads_total{result="hit",tier="L2"}[5m]))
  / sum(rate(frontcache_cache_reads_total{result="hit"}[5m]))
```

A high ratio served almost entirely from `tier="L2"` means requests are hitting, but off
disk; a rising `frontcache_cache_evictions_total{tier="L1"}` confirms it.
`frontcache_cache_disk_bytes` answers "will the cache volume fill up", which the entry count
cannot — page sizes vary by orders of magnitude.

Before wiring a scraper:

- **`/fc-metrics` answers only on `front-cache.management.port`** when that is set — the same
  rule as the dashboard stream. Point the scrape job at that connector.
- **If you use guard rules, allow it.** Frontcache warns at startup when a rule would block
  it, the same way it does for `/frontcache-io` and the stream.
- **A tier that is not tracked emits no series, not a zero** — the in-memory cache processor
  has no `tier="L2"` at all. Do not write alerts that assume the series exists.
- **Scraping does not start the cache**: a node whose cache has not initialised reports the
  read counters as zeros and no size series, rather than having its Lucene index opened by
  the scrape.
- **Labels are a small fixed set** — `command`, `event`, `quantile`, `pool`, `name`, `kind`,
  `state`, `result`, `tier`. **Nothing is labelled by URL**, deliberately: on a page cache the
  URL space is unbounded, so a per-URL label is a way to take down a Prometheus server.

## 3. OTLP push — collectors, Grafana Alloy, vendor backends

For anything that would rather be pushed to than scraped:

```properties
front-cache.metrics.export=otlp                                  # or: both
front-cache.metrics.otlp.url=http://collector:4318/v1/metrics
front-cache.metrics.otlp.step=60s
front-cache.metrics.otlp.headers=Authorization=Bearer ...
```

Same series, same names. `otlp.url` is required — without it OTLP export stays **off**
rather than logging a delivery failure every step. Header values are treated as secrets and
never logged.

## 4. The SSE stream — external Hystrix Dashboard / Turbine

The node streams the same resilience numbers as Server-Sent Events at
**`/fc-dashboard.stream`**, and at the pre-2.7 **`/hystrix.stream`**, which is kept
indefinitely. The JSON is unchanged and pinned — including the `"type":"HystrixCommand"`
literal — precisely so an external Hystrix Dashboard or Turbine can keep reading it.

- Requires the node's site key; a missing or wrong one is a `401`.
- Management-port rule applies, as above.
- **Do not buffer it** through a reverse proxy — see the nginx recipe in
  [examples/front-door](../examples/front-door).

Use this for the live per-command view. Use Prometheus (§2) for anything you want to keep,
alert on, or graph over time — the stream is a rolling window, not a store.

## 5. Logs → Elasticsearch + Kibana

Four logs under `FRONTCACHE_HOME/logs/` — `frontcache-requests.log` (one line per request
*and* per `<fc:include>` fragment: cache status, latency, bytes, client, bot flag),
`error.log`, `fallback.log`, `frontcache-failed-requests.log` (guard-rule rejections,
redirects, dry-run matches).

This is the only channel with **per-URL** data, which is exactly why it is a log and not a
metric. [examples/log-analytics](../examples/log-analytics) pulls them off the hosts and
indexes all four into a local Elastic stack with four ready-made dashboards.

For a one-off, `front-cache.log-to-headers=true` puts the same per-request timings into
`x-frontcache-*` response headers — no log pipeline, and readable in browser devtools.

---
