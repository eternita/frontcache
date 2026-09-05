# Frontcache standalone server — install

This bundle is the reverse-proxy mode of Frontcache: it sits in front of an origin web app
written in any language, serves cached page fragments, stitches `<fc:include url="..."/>`
fragments, and circuit-breaks origin calls with Resilience4j fallbacks.

```
bin/frontcache                          convenience wrapper
server/bin/frontcache                   the launcher
server/lib/*.jar                        launcher classpath (embedded Jetty 12 / Jakarta EE10)
server/frontcache-base/webapps/ROOT.war the webapp
server/frontcache-base/etc/keystore     Jetty DEMO keystore — self-signed, replace it
FRONTCACHE_HOME/conf/                   all configuration
FRONTCACHE_HOME/cache/                  L2 Lucene index (created at runtime)
FRONTCACHE_HOME/logs/                   request, error, fallback and rejected-request logs
FRONTCACHE_HOME/fallbacks/              fallback response bodies
FRONTCACHE_HOME/warmer/                 cache-warmer helper
```

## 1. Prerequisites

A **JDK 25**. The launcher runs embedded Jetty 12 (Jakarta EE10) and the webapp is Java 25
bytecode, so an older JVM will not load it.

```bash
java -version      # must report 25 or newer
```

On Ubuntu 24 (noble) `openjdk-25-jdk` is not in the default repos — use the Adoptium
(Eclipse Temurin) apt repository, or set `FRONTCACHE_JAVA_HOME` to a JDK 25 you unpacked
yourself.

## 2. Configure

Everything lives in `FRONTCACHE_HOME/conf/`. Edit `frontcache.properties` first:

| Property | Set it to |
|---|---|
| `front-cache.origin-host` | the backend Frontcache forwards cache misses to |
| `front-cache.origin-http-port` / `-https-port` | the backend's ports |
| `front-cache.default-domain` | your public domain |
| `front-cache.site-key` | a secret of your own — **this is what guards the management API**, the dashboard stream and `/fc-metrics`. Send it as `Authorization: Bearer <site-key>` — one form for the agent, the console, node-to-node replication and a Prometheus scrape. The pre-2.7 `x-frontcache-site-key` header still works, deprecated |
| `front-cache.http-port` / `front-cache.https-port` | the **client-facing** ports (80/443 when a TLS terminator fronts Frontcache), *not* the 9080 the launcher listens on — these two are what redirect rewriting uses |

Then, as needed:

- `frontcache.id` — this node's identity, returned in the `x-frontcache-id` response header.
  Give every node a distinct value so traces are attributable.
- `dynamic-urls.conf` — URL patterns that must never be cached (cart, login, admin).
- `bots.conf` — user-agent keywords treated as bots.
- `fallbacks.conf` — what to serve when the origin 5XXs or a circuit opens.
- `guard-rules.conf` — checks applied before cache and origin (reject / redirect / dry-run).
- `fc-l1-ehcache-config.xml` — L1 in-memory cache sizing.
- `resilience.properties` — circuit-breaker thresholds and thread pools. (Pre-2.7 installs called
  this `hystrix.properties`; that name is still read for one release, with a warning, and the
  `hystrix.*` keys inside it are accepted indefinitely.)

## 3. Run

```bash
./server/bin/frontcache
```

It listens on **9080** (HTTP only — terminate TLS in front of it). Override with
`FRONTCACHE_HTTP_PORT`. Startup prints the resolved `FRONTCACHE_HOME` and ends with
`Frontcache Jetty Server has been started successfully`.

## 4. Verify

Request a cacheable URL twice. With `front-cache.log-to-headers=true` (the shipped default)
the response carries the Frontcache timing and trace headers, and the second request should
report the fragments as coming from cache. Logs are under `FRONTCACHE_HOME/logs/`.

## 5. Put it in front of traffic

1. Terminate TLS upstream (nginx, ALB) on 80/443 and proxy to `http://127.0.0.1:9080`.
   Forward `X-Forwarded-*` — the launcher honours them, which is what keeps absolute-URL
   rewriting correct behind a proxy. Set `front-cache.site-key` and require it, or anything
   that can reach a connector can reach the management API; if you want management traffic
   confined to one connector, restrict `/frontcache-io`, `/fc-dashboard.stream` and
   `/fc-metrics` in the proxy (the node no longer has a `front-cache.management.port` of
   its own — it compared a Host header the caller supplies, so it restricted nobody).
   Do **not** buffer `/fc-dashboard.stream` (or its pre-2.7 alias `/hystrix.stream`); they are
   Server-Sent-Events streams.
   Set `front-cache.http-port` / `front-cache.https-port` to the ports **clients** see
   (80/443), not this one, or redirects will send users to `:9080`.
   A worked nginx front door — two containers, or a script for this exact layout — is at
   <https://github.com/eternita/frontcache/tree/master/examples/front-door>.
2. Move DNS to the Frontcache host and restrict the origin so only Frontcache can reach it.
3. Run it under a supervisor. A minimal systemd unit:

   ```ini
   [Unit]
   Description=Frontcache standalone server
   After=network.target

   [Service]
   Type=simple
   User=frontcache
   WorkingDirectory=/opt/frontcache/server/bin
   ExecStart=/opt/frontcache/server/bin/frontcache
   SuccessExitStatus=143
   Restart=on-failure
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```

## 6. Make the origin declare what is cacheable

Frontcache caches nothing by default. The origin opts each fragment in with response headers:

```
x-frontcache-component-maxage: 1h          # 0 = no cache, -1/forever = forever; bot:/browser: prefixes allowed
x-frontcache-component-tags: catalog|product-42
x-frontcache-component-refresh: soft       # serve stale while revalidating
x-frontcache-component-cache-level: L2     # L1 (memory) or L2 (disk, default)
```

and marks fragment boundaries in the HTML it returns:

```html
<fc:include url="/store/product-details-42"/>
```

Java/JSP origins can use the taglib (`<fc:component maxage="1h" tags="catalog"/>`) instead
of setting headers by hand.

## 7. Invalidate from your application

Add `org.frontcache:frontcache-agent` (`https://repo.eternita.co/maven2`) to the app and call
`FrontCacheAgent` for a single node or `FrontCacheAgentCluster` for a multi-node fleet. Both
authenticate with `front-cache.site-key`.

## Upgrading

Unpack the new version alongside the old one and copy your `FRONTCACHE_HOME/conf/` across —
never let a new bundle's `conf/` overwrite a live one. Compare against the shipped defaults
for new properties. The `cache/` directory is pure cache and can be discarded at any time.

---

Console (realtime stats, cache management) ships separately as `frontcache-console-<version>.zip`.

Licensing: https://www.eternita.co/frontcache-license.html
Docs: https://github.com/eternita/frontcache/wiki
