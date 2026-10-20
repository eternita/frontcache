# Frontcache console — install

The console is a web UI for realtime stats and cache management across one or more Frontcache
nodes. It is a **separate process** from the standalone server (the server's launcher deploys
exactly one webapp at context `/`), and it talks to each node over that node's management API.

```
bin/frontcache-console   the launcher
lib/*.jar                launcher classpath (embedded Jetty 12 / Jakarta EE10)
webapps/ROOT.war         the console webapp (Spring MVC + frontcache-core in WEB-INF/lib)
conf/frontcache-console.conf   the nodes to monitor, the shared api-key, the console login
conf/fc-logback.xml            logging (stdout, WARN and above)
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
        apiKey = your-api-key

        user = admin
        password = admin
    }
}
```

- `urls` — one entry per Frontcache node, including scheme and port. The console polls each
  node's management API for cache state and stats.
- `apiKey` — must match `front-cache.api-key` in every node's `frontcache.properties`.
  Without it the nodes reject the console's calls.
- `user` / `password` — the console's single login (one user, no roles). A browser signs in on the
  console's own login page; `curl`, the container healthcheck and anything already automated keep
  presenting the same pair as HTTP Basic. **Change the password**: `admin`/`admin` is what the
  bundle ships with, and the console warns on every startup until it is something else.

  Remove **both** lines to run without authentication — the console then warns about *that* on
  every startup instead, and `/login` stops being a page. Setting one without the other is a
  startup failure, not a guess. Add `auth-required = true` to refuse to start rather than run
  unauthenticated.
- `session-cookie-secure` — adds `Secure` to the session cookie. Default false; turn it on when the
  console is reached over HTTPS, including through a TLS-terminating proxy, which the console cannot
  detect for itself. With it on behind plain HTTP the login page accepts the password and comes
  straight back, because the cookie is never sent.


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

1. **Change the password** in `conf/frontcache-console.conf` (§2). The shipped `admin`/`admin` is
   a published default, and the console says so at startup until you change it.
2. **Terminate TLS in front of it, or keep it on an internal network**, and set
   `session-cookie-secure = true` when you do. The session cookie is a bearer token, and the Basic
   path — which `curl` and the healthcheck still use — sends `user:password` base64-encoded on
   *every* request, which is encoding, not encryption. Authentication makes the console safe to
   leave running; it does not make it safe to publish.
3. **For more than one operator, put it behind an authenticating reverse proxy** (nginx,
   oauth2-proxy, your SSO). One shared password has no accountability — nothing in a log can say
   *who* flushed the cache — and no revocation short of changing it for everybody.

**Sign out** is in the top right of every screen, beside the user name. It ends the browser's
session; a restart of the console ends every session, since they are held in memory and not
persisted. It does not affect a `curl` that sends the credential on each call — there is nothing
there to end.

## Upgrading

Unpack the new version and copy your `conf/frontcache-console.conf` across. The console holds
no state of its own.

---

License: Apache-2.0 - see LICENSE, NOTICE and THIRD-PARTY-NOTICES.txt in this bundle.
Frontcache Pro (the cache warmer, the cluster view and, to come, RBAC, SSO and an audit log) is
available under a commercial license: https://www.eternita.co/frontcache-license.html
Docs: https://github.com/eternita/frontcache/wiki
