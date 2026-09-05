# Frontcache console — install

The console is a web UI for realtime stats and cache management across one or more Frontcache
nodes. It is a **separate process** from the standalone server (the server's launcher deploys
exactly one webapp at context `/`), and it talks to each node over that node's management API.

```
bin/frontcache-console   the launcher
lib/*.jar                launcher classpath (embedded Jetty 12 / Jakarta EE10)
webapps/ROOT.war         the console webapp (Spring MVC + frontcache-core in WEB-INF/lib)
conf/frontcache-console.conf   the nodes to monitor + the shared site-key
conf/fc-logback.xml            logging (stdout, ERROR and above)
```

## 1. Prerequisites

A **JDK 25** — same requirement as the server bundle.

```bash
java -version
```

## 2. Configure

Edit `conf/frontcache-console.conf`. It is a HOCON file:

```hocon
frontcache {
    console {
        urls = ["https://fc-edge.example.com:443/", "https://fc-eu.example.com:443/"]
        siteKey = your-site-key
    }
}
```

- `urls` — one entry per Frontcache node, including scheme and port. The console polls each
  node's management API for cache state and stats.
- `siteKey` — must match `front-cache.site-key` in every node's `frontcache.properties`.
  Without it the nodes reject the console's calls.


## 3. Run

```bash
./bin/frontcache-console
```

It listens on **7080**; override with `FRONTCACHE_CONSOLE_PORT`. To point it at a config file
outside the bundle, export `FRONTCACHE_CONFIG=/path/to/frontcache-console.conf` — the launcher
only supplies its own `conf/` copy as the default.

Then open `http://localhost:7080/`.

## 4. Secure it

The console can invalidate cache across the whole fleet, so **do not expose it publicly**.
Keep it on an internal network, or put it behind an authenticating reverse proxy. It has no
authentication of its own.

## Upgrading

Unpack the new version and copy your `conf/frontcache-console.conf` across. The console holds
no state of its own.

---

Licensing: https://www.eternita.co/frontcache-license.html
Docs: https://github.com/eternita/frontcache/wiki
