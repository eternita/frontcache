# Frontcache — Security

Notes on what protects what. Current as of **2.9.0**.

The model is small, and worth stating plainly because two of its three parts are weaker than
their names suggest:

| | What it is | What it actually gives you |
| --- | --- | --- |
| `front-cache.api-key` | a shared secret on management requests | **the access control.** This is the one that decides *who may* |
| `front-cache.management.port` | **removed in 2.9.0** | nothing — it was a routing hint, never a boundary; see below |
| the console | the UI for all of it | **no authentication of its own** |

Three management surfaces exist, and as of 2.9.0 they are protected alike. The management API
(`/frontcache-io`), the dashboard stream (`/fc-dashboard.stream`, `/hystrix.stream`) and the
Prometheus scrape (`/fc-metrics`) all require the api key. There is nothing else in front of
them — the management port that used to be described here is gone (§2).

---

## 1. The api key

```properties
front-cache.api-key=CHANGE_ME        # guards the management API
```

Callers send it as a header:

```sh
curl -H "Authorization: Bearer YOUR_API_KEY" \
  "http://<edge>/frontcache-io?action=get-cache-status"
```

- **It ships as the literal `CHANGE_ME`.** So does `front-cache.default-domain`. Editing both is
  step one of any install.
- **A node with no api key configured is OPEN.** `ApiKey.isAuthorized` returns true when the
  property is empty — the historical default, kept so an upgrade does not refuse traffic. The
  node warns once at startup. This is the single most important line on this page: an unset key
  is not "no management API", it is "an unauthenticated one".
- Comparison is constant-time (`MessageDigest.isEqual`), so the key is not discoverable by
  timing.
- A wrong or missing key on the dashboard stream is a **401** (since 2.6.0). An edge the console
  cannot reach is a **502** (new in 2.7) — previously an empty `200`, which looked like "no data"
  rather than "not reachable".

## 2. There is no management port — removed in 2.9.0

`front-cache.management.port` **no longer exists.** If your `frontcache.properties` still sets it,
the property is simply ignored; nothing fails, and nothing it used to do is missing, because it
never did what its name suggested.

It compared `request.getServerPort()`, which the Servlet spec derives from the **Host header** — so
it reported the port the caller *addressed*, not the socket the connection arrived on. Anyone who
could reach any connector could send `Host: whatever:443` and satisfy it. It was a routing hint a
caller could satisfy at will, and never an access control.

**The api key is the only access control.** Confining management traffic to one connector is the
reverse proxy's job, not the application's: terminate TLS upstream, and route `/frontcache-io`,
`/fc-dashboard.stream` and `/fc-metrics` only from where you intend them to be reachable. A node
with no api key set has nothing enforcing anything, and says so once at startup.

`/fc-metrics` sits behind the same port rule. A scrape endpoint is unauthenticated by
convention, so at 2.7 the connector is its only control — keep it internal, and if you run guard
rules, allow it (Frontcache warns at startup when a rule would block it).

## 3. The console

- **No authentication of its own, and it can invalidate cache across your whole fleet.** Keep it
  on loopback (`-p 127.0.0.1:7080:7080`), on an internal network, behind an ssh tunnel, or behind
  an authenticating proxy. Never public.
- Its `apiKey` must match each node's `front-cache.api-key`, so the console holds a credential
  for every node it watches. Treat `conf/frontcache-console.conf` as a secret.
- **2.7 closed a real hole in the stream proxy.** It used to fetch whatever URL was passed in its
  `origin` parameter — *and attach your api key to the request*. A crafted `origin` could
  therefore aim an authenticated request at a host of the caller's choosing. It now refuses
  anything not in `frontcache.console.urls` (or added via the Edges page) and appends the stream
  path itself. If you scripted against `/resources/hystrix/proxy.stream`, it is now
  `/fc-dashboard-proxy.stream` and takes an edge URL.
- The refusal is logged at `WARN` — which means nothing if you carried a 2.6
  `conf/fc-logback.xml` over, because the shipped file filtered stdout at `ERROR`. Change the
  `STDOUT` appender threshold to `WARN` or you will not see it.

## 4. Invalidation — the blast radius

Anything holding the api key can empty the cache. That is the point of the key.

```java
new FrontCacheAgent("http://fc-host:9080", apiKey).removeFromCache("/store/product/42.*");
cluster.removeFromCache("/store/product/42.*");   // fans out to every node
```

- **`filter=*` empties the whole node**, replicated entries included. There is no confirmation
  step; it is one HTTP call.
- Cluster invalidation fans the `invalidate` action out to every node, so **one key is one
  fleet-wide blast radius**. The cluster deliberately shares a key so invalidation lines up
  everywhere — that convenience and the blast radius are the same property.
- Origin apps embed `frontcache-agent`, so the key lives in application config wherever that runs.
  It is a deploy-time secret, not a build-time constant.
- An unrecognized action returns a **ping** response rather than an error (version negotiation),
  so a typo'd action name fails quietly rather than loudly.

## 5. Guard rules — rejecting traffic before cache or origin

[guard-rules.conf](guard-getting-started.md) answers requests before they touch the cache or
your origin, which makes it the cheapest place to shed abuse. Two security-relevant details:

- **Open-redirect protection.** An absolute `redirect:` target must have its host listed in
  `front-cache.guard-rules.allowed-redirect-hosts`, and **nothing is allowed implicitly** — not
  even `front-cache.default-domain`. A rule naming an unlisted host is refused at load.
- **Do not lock yourself out.** An `ip-access`-style rule must exempt `/fc-ping.jsp`,
  `/frontcache-io`, and both dashboard-stream paths, or you redirect your own health checks,
  agents, console and replication. Frontcache warns at startup when it spots this.

## 6. Transport and headers

- **The container serves plain HTTP on 9080 and terminates no TLS.** That is by design — put an
  ingress, ALB, Cloudflare, or the [front-door nginx example](../examples/front-door) in front.
- **Client-IP headers are client-controllable.** `FCUtils.getClientIP` takes the first present of
  `x-frontcache-client-ip`, `x-forwarded-for`, `Proxy-Client-IP`, … before falling back to
  `getRemoteAddr()`. So guard rules keyed on IP or country, and the `clientip` field in the logs,
  are only as trustworthy as the front door: it must **overwrite** those headers on the way in,
  not append. Note that `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` (what the
  front-door example uses, and the nginx idiom) *appends* — and nothing strips a
  client-supplied `x-frontcache-client-ip`, which is checked first.
- `x-frontcache-*` response headers expose cache status and timings; `front-cache.log-to-headers`
  adds per-request internals. Fine internally, worth stripping at the edge for public traffic.

## 7. Logs and the analytics stack

- The four logs under `logs/` carry **full URLs, client IPs, and user agents** — treat them as
  personal data with whatever retention that implies for you.
- The [log-analytics example](../examples/log-analytics) runs Elasticsearch, Kibana and
  Logstash **with security disabled**. It is explicitly a workstation tool, not a deployment.
  Do not put it on a shared host as-is.

---

## Minimum checklist

1. `front-cache.api-key` is not `CHANGE_ME`, and is set on **every** node.
2. Management paths (`/frontcache-io`, `/fc-dashboard.stream`, `/fc-metrics`) are reachable only
   from where you intend, enforced at your reverse proxy or firewall — not by any node property.
3. The console is not publicly reachable, and its config file is treated as a secret.
4. TLS terminates in front of Frontcache.
5. The front door overwrites client-IP headers if any rule or report depends on them.
6. Guard rules exempt `/fc-ping.jsp`, `/frontcache-io` and both stream paths.
7. `conf/fc-logback.xml` filters stdout at `WARN`, so refusals are visible.

---
