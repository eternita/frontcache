# Frontcache — Install Guide



| | What you install | Prerequisites | Best for                                                    |
|---|---|---|-------------------------------------------------------------|
| **A. Library** | `frontcache-core` on your app's classpath | your build tool, JDK 25 | a Java based web app that should cache itself (use case #1) |
| **B. Archive** | a `.tar.gz`/`.zip` you unpack | JDK 25 — or **nothing**, with a bundled-runtime build | manual installs, air-gapped hosts, evaluation               |
| **C. Installer** | the same archive, as a systemd service | root on Linux | VMs and fleets (use cases #2, #3)                           |
| **D. Container** | a Docker image | Docker | containers, Kubernetes, quickest trial, **and Windows**     |
| **E. Console** | the management UI | JDK 25 or Docker | realtime stats, cache invalidation                          |

Frontcache 2.6.0 requires **Java 25** and is **Jakarta EE 10** (`jakarta.servlet`, Servlet 6.0).
It will not load in a `javax.servlet` container or on an older JVM. The container images and the
bundled-runtime archives carry their own runtime, so those two need no JDK at all.

---

## A. Library — Frontcache inside your Java app

Use case #1. Frontcache runs in your app's JVM as a servlet filter; your app is its own origin.

**1. Add the dependency**

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://repo.eternita.co/maven2' }
}
dependencies {
    implementation 'org.frontcache:frontcache-core:2.6.0'
    implementation 'org.frontcache:frontcache-agent:2.6.0'   // optional: invalidate from app code
}
```

```xml
<repositories>
  <repository><id>eternita</id><url>https://repo.eternita.co/maven2</url></repository>
</repositories>
<dependency>
  <groupId>org.frontcache</groupId><artifactId>frontcache-core</artifactId><version>2.6.0</version>
</dependency>
```

**2. Get a `FRONTCACHE_HOME` skeleton**

Frontcache reads its configuration from a directory, not from your app's config:

```sh
curl -fLO https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.6.0/frontcache-core-2.6.0-home.zip
unzip frontcache-core-2.6.0-home.zip
```

It contains a filter-mode `conf/frontcache.properties` plus `README-FILTER.md` with the rest of
the steps. Edit at least `front-cache.default-domain` and `front-cache.site-key` — both ship as
`CHANGE_ME`.

**3. Register the filter**

```xml
<filter>
    <filter-name>FrontCacheFilter</filter-name>
    <filter-class>org.frontcache.FrontCacheFilter</filter-class>
</filter>
<filter-mapping>
    <filter-name>FrontCacheFilter</filter-name>
    <url-pattern>/example/*</url-pattern>
</filter-mapping>
```

Spring Boot, with no `web.xml` — see
[the Spring example](../examples/frontcache-spring/src/main/java/org/frontcache/example/WebConfig.java):

```java
@Bean
FilterRegistrationBean<FrontCacheFilter> frontcacheFilter() {
    var reg = new FilterRegistrationBean<>(new FrontCacheFilter());
    reg.addUrlPatterns("/example/*");
    reg.setName("FrontCacheFilter");
    reg.setOrder(1);
    return reg;
}
```

**4. Launch your container with**

```
-Dfrontcache.home=/path/to/FRONTCACHE_HOME
-Dlogback.configurationFile=/path/to/FRONTCACHE_HOME/conf/fc-logback.xml
```

**Working examples:** [`frontcache-jsp`](../examples/frontcache-jsp) (Gradle + JSP) and
[`frontcache-spring`](../examples/frontcache-spring) (Maven + Spring Boot). Both run with one
command and show cached vs. dynamic fragments in the response headers.

---

## B. Archive — unpack and run

Use case #2: a standalone reverse proxy in front of an app in any language.

```sh
V=2.6.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V

curl -fLO $BASE/frontcache-server-$V.tar.gz
curl -fLO $BASE/frontcache-server-$V.tar.gz.sha256
# the published checksum may be a bare hash, so compare the hash field
# rather than using `shasum -c`, which needs the `hash  filename` form:
[ "$(shasum -a 256 frontcache-server-$V.tar.gz | cut -d' ' -f1)" \
  = "$(cut -d' ' -f1 < frontcache-server-$V.tar.gz.sha256)" ] && echo "checksum OK"

sudo tar -xzf frontcache-server-$V.tar.gz -C /opt
sudo ln -sfn /opt/frontcache-server-$V /opt/frontcache
```

`.zip` is published alongside if you prefer it. **Always check the checksum** — it is published
for every artifact.

### No JDK? Use a bundled-runtime build

Same bundle plus an embedded Java runtime, so nothing else has to be installed:

```sh
curl -fLO $BASE/frontcache-server-$V-linux-x64.tar.gz          # or -linux-aarch64, -macos-aarch64
curl -fLO $BASE/frontcache-server-$V-linux-x64.tar.gz.sha256
sha256sum -c frontcache-server-$V-linux-x64.tar.gz.sha256
sudo tar -xzf frontcache-server-$V-linux-x64.tar.gz -C /opt
```

It unpacks to the **same** directory name with one extra `runtime/` directory, and the launcher
prefers that runtime over any `JAVA_HOME` on the host — so every step below is identical.
(No Windows build: use the container there.)

### Configure

Edit `/opt/frontcache/FRONTCACHE_HOME/conf/frontcache.properties`:

```properties
front-cache.origin-host=app.internal.example.com    # where cache misses go
front-cache.origin-http-port=8080
front-cache.origin-https-port=8443

front-cache.default-domain=www.example.com
front-cache.site-key=CHANGE_ME                      # guards the management API

# The CLIENT-FACING ports. These drive redirect rewriting, so behind a TLS terminator they are
# 80/443 - NOT the 9080 Frontcache itself listens on.
front-cache.http-port=80
front-cache.https-port=443

front-cache.management.port=443                     # firewall this
```

Then, as needed: `dynamic-urls.conf` (never-cache paths), `bots.conf`, `fallbacks.conf`,
`guard-rules.conf`, `fc-l1-ehcache-config.xml`, `hystrix.properties`, and `conf/frontcache.id`
(give every node a distinct value — it comes back in the `x-frontcache-id` header).

### Run

```sh
/opt/frontcache/bin/frontcache          # HTTP on 9080; FRONTCACHE_HTTP_PORT overrides
```

Startup ends with `Frontcache Jetty Server has been started successfully`.

### Verify

```sh
curl -s -o /dev/null -w '%{http_code}\n' \
  "http://127.0.0.1:9080/frontcache-io?action=get-cache-state"
```

`200` means Frontcache is live. The body will say *access denied* unless the request arrived on
`front-cache.management.port` — that is expected, and is exactly why this works as a health check
without a site key.

### Put it in front of traffic

1. Terminate TLS upstream (nginx, ALB) on 80/443 and proxy to `http://127.0.0.1:9080`. Forward
   the `X-Forwarded-*` headers — Frontcache honours them, which is what keeps redirect rewriting
   and the management-port check correct behind a proxy. Do **not** buffer `/hystrix.stream`; it
   is a Server-Sent-Events stream. Set `front-cache.http-port` / `front-cache.https-port` to the
   ports **clients** see (80/443), not 9080, or redirects will send users to `:9080`.
   A worked nginx front door — a two-container compose stack, or a script for a VM — is in
   [examples/front-door](../examples/front-door).
2. Move DNS to the Frontcache host, and restrict the origin so only Frontcache can reach it.
3. Run it under a supervisor — `README-INSTALL.md` inside the bundle carries a systemd unit, or
   use channel C, which writes it for you.

### Upgrade

Unpack the new version beside the old one, copy your `conf/` across, flip the symlink, restart.
Never let a new bundle's `conf/` overwrite a live one. `cache/` is pure cache — discard it freely.

---

## C. Installer — a systemd service on a VM

Everything in channel B, scripted, including the JDK and the service user. It installs
Frontcache and nothing else — a front door on 80/443 is a separate, optional step, see
[examples/front-door](../examples/front-door).

```sh
V=2.6.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V

curl -fLO $BASE/frontcache-server-$V-installer.sh
curl -fLO $BASE/frontcache-server-$V-installer.sh.sha256
# the published checksum may be a bare hash, so compare the hash field
# rather than using `shasum -c`, which needs the `hash  filename` form:
[ "$(shasum -a 256 frontcache-server-$V-installer.sh | cut -d' ' -f1)" \
  = "$(cut -d' ' -f1 < frontcache-server-$V-installer.sh.sha256)" ] && echo "checksum OK"

sudo bash frontcache-server-$V-installer.sh --version $V \
     --origin-host origin.example.com
```

Download it, check it, then run it — deliberately **not** `curl | sudo bash`. The checksum step
is the point for a script that runs as root.

Useful flags:

| Flag | Effect |
| --- | --- |
| `--with-runtime` | install the build that carries its own Java runtime; no JDK is installed or needed |
| `--with-console` | also install the console as a second service on 7080 (loopback) |
| `--archive PATH` | install from a local file instead of downloading — air-gapped hosts |
| `--dry-run` | print every step, change nothing |
| `--dir`, `--user`, `--port` | install root, service account, Frontcache port |

Then:

```sh
systemctl status frontcache
journalctl -u frontcache -f
```

**Upgrading** is the same command with a newer `--version`. Your config is carried across
untouched; where a *shipped default* changed, the new default is written beside yours as
`<file>.new` with a `diff` command printed. **Rolling back** is a symlink flip — the previous
version is still on disk, and the installer prints the exact command.

`uninstall` removes the services and units, and deliberately leaves your config, cache and logs
in place.

---

## D. Container image

The fastest way in, and the answer on Windows.

```sh
docker run -d --name frontcache --restart unless-stopped \
  -p 9080:9080 \
  -e ORIGIN_HOST=origin.example.com \
  -v /srv/frontcache/conf:/opt/frontcache-server/FRONTCACHE_HOME/conf \
  -v fc-cache:/opt/frontcache-server/FRONTCACHE_HOME/cache \
  pavlikovskiy/frontcache-server:2.6.0
```

The image is **Frontcache alone** — plain HTTP on 9080, no TLS. That is what a Kubernetes
ingress, an ALB, or Cloudflare wants in front of it. For a front door of your own on 80/443, see
[examples/front-door](../examples/front-door): nginx and Frontcache as two containers, or a
script for a VM.

It is multi-arch (amd64 + arm64) and carries a `HEALTHCHECK`.

### With compose

```sh
V=2.6.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V
curl -fLO $BASE/frontcache-server-$V-compose.yml
curl -fL  $BASE/frontcache-server-$V-env.example -o .env
$EDITOR .env                                    # ORIGIN_HOST, ORIGIN_PATHS, ports
docker compose -f frontcache-server-$V-compose.yml up -d
docker compose -f frontcache-server-$V-compose.yml --profile console up -d   # + the console
```

### Things to know

- **Config is upgrade-safe.** The image keeps its defaults outside `conf/` and seeds only the
  files that are *missing*, so a new image can add config to an existing volume without touching
  what you edited. A host bind mount (`-v /srv/frontcache/conf:...`) is the production
  recommendation.
- **`ORIGIN_HOST` decides who owns the setting.** Set it, and it is written into
  `frontcache.properties` on every start. Leave it unset, and your mounted file is the source of
  truth and is never rewritten.
- **TLS is not this container's job.** It serves plain HTTP; terminate TLS in front of it.
- **Upgrade** with `docker compose pull && docker compose up -d` on a new tag. The `cache` volume
  is pure cache and can be dropped at any time.
- Pin the exact version. `latest` exists and currently points at 2.6.0; naming it in production is how you get surprised.

---

## E. Console — realtime stats and cache management

The console is a **separate process** from the server, on port 7080.

```sh
docker run -d --name frontcache-console --restart unless-stopped \
  -p 127.0.0.1:7080:7080 \
  -e FC_NODES=http://fc-server:9080/ -e FC_SITE_KEY=YOUR_SITE_KEY \
  pavlikovskiy/frontcache-console:2.6.0
```

Or as an archive:

```sh
V=2.6.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-console/$V
curl -fLO $BASE/frontcache-console-$V.tar.gz
curl -fLO $BASE/frontcache-console-$V.tar.gz.sha256
# the published checksum may be a bare hash, so compare the hash field
# rather than using `shasum -c`, which needs the `hash  filename` form:
[ "$(shasum -a 256 frontcache-console-$V.tar.gz | cut -d' ' -f1)" \
  = "$(cut -d' ' -f1 < frontcache-console-$V.tar.gz.sha256)" ] && echo "checksum OK"
tar -xzf frontcache-console-$V.tar.gz
$EDITOR frontcache-console-$V/conf/frontcache-console.conf     # node urls + siteKey
./frontcache-console-$V/bin/frontcache-console                 # :7080
```

`siteKey` must match each node's `front-cache.site-key`, and each node must admit the console
through `front-cache.management.port`.

> **The console has no authentication of its own** and can invalidate cache across your whole
> fleet. Keep it on loopback or an internal network, reach it over an ssh tunnel, or put an
> authenticating proxy in front of it. Do not expose it publicly.

---

## Telling the origin what to cache

Frontcache caches **nothing** until the origin says so — in any language, via response headers:

| Header | Meaning |
| --- | --- |
| `x-frontcache-component-maxage` | TTL. `0` = never cache (default), `-1`/`forever` = forever, or `60`/`15m`/`24h`. `bot:` / `browser:` prefixes give crawlers and users different TTLs |
| `x-frontcache-component-tags` | pipe-separated invalidation tags, e.g. `catalog\|product-42` |
| `x-frontcache-component-refresh` | `soft` = serve stale while revalidating |
| `x-frontcache-component-cache-level` | `L1` (memory) or `L2` (disk, default) |

and fragment markers in the HTML the origin returns:

```html
<fc:include url="/store/product-details-42"/>
```

Java/JSP origins can use the taglib instead:

```jsp
<%@ taglib uri="http://frontcache.org/core" prefix="fc" %>
<fc:component maxage="1h" tags="catalog|product" refresh="soft" level="L2" />
<fc:include url="/common/header.jsp" />
<fc:include url="/common/recommendations" call="async" />
<fc:include url="/seo/footer" client="bot" />
```

## Invalidating

From Java:

```java
new FrontCacheAgent("http://fc-host:9080").removeFromCache(siteKey, "/store/product/42.*");
```

`FrontCacheAgentCluster` fans the same call out to every node in a multi-region deployment. From
anything else, the management API is plain HTTP:

```sh
curl -s -H "x-frontcache-site-key: YOUR_SITE_KEY" \
  "http://fc-host:9080/frontcache-io?action=invalidate&filter=/store/product/42.*"
```

---

## Download reference

Everything lives under `https://repo.eternita.co/maven2/org/frontcache/`, and every artifact has
a companion `.sha256`.

| What | Coordinate / file |
| --- | --- |
| Library | `org.frontcache:frontcache-core:2.6.0` |
| Config skeleton | `frontcache-core-2.6.0-home.zip` |
| Invalidation client | `org.frontcache:frontcache-agent:2.6.0` |
| Standalone server | `frontcache-server-2.6.0.tar.gz` / `.zip` |
| Server, bundled runtime | `frontcache-server-2.6.0-{linux-x64,linux-aarch64,macos-aarch64}.tar.gz` |
| Console | `frontcache-console-2.6.0.tar.gz` / `.zip` (+ the same platform builds) |
| Installer | `frontcache-server-2.6.0-installer.sh` |
| Compose + env | `frontcache-server-2.6.0-compose.yml`, `-env.example` |
| Container, server | `pavlikovskiy/frontcache-server:2.6.0` |
| Container, console | `pavlikovskiy/frontcache-console:2.6.0` |

---

## Troubleshooting

**Nothing is being cached.** Expected until the origin opts in — check the
`x-frontcache-component-maxage` header on the origin's own response. Set
`front-cache.log-to-headers=true` and read the `x-frontcache-trace-request.*` headers: they say
per fragment whether it was `from-cache`, `dynamic`, or `error`.

**Every request 503s or 500s.** Frontcache is up but cannot reach the origin. Check
`front-cache.origin-host` and the origin ports, and look at `FRONTCACHE_HOME/logs/error.log`. The
shipped config points at the `origin.example.com` placeholder, so an untouched install does
exactly this.

**Redirects go to the wrong port.** `front-cache.http-port` / `front-cache.https-port` must be
the **client-facing** ports, not the port Frontcache listens on.

**`UnsupportedClassVersionError` on startup.** The JVM is older than 25. Use a bundled-runtime
archive, a container, or point `FRONTCACHE_JAVA_HOME` at a JDK 25.

**The management API returns "access denied".** The request did not arrive on
`front-cache.management.port`, or the `x-frontcache-site-key` header does not match.

**Logs.** `FRONTCACHE_HOME/logs/`: `frontcache-requests.log` (one line per request/fragment),
`error.log`, `fallback.log`, `frontcache-failed-requests.log` (guard-rule rejections and Hystrix
fallbacks). The [log-analytics example](../examples/log-analytics) indexes all four into
Elasticsearch + Kibana with ready-made dashboards.

---

Concepts: [concept.md](concept.md) ·
Topologies: [deployment-usecases.md](deployment-usecases.md) ·
Licensing: <https://www.eternita.co/frontcache.html>
