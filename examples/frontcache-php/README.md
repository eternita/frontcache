### 'frontcache-php' - Frontcache in front of a PHP app ###

Example project path: ./examples/frontcache-php

This is **use case #2**: Frontcache as a standalone reverse proxy in front of an app written in
any language. Nothing here is Java — the PHP pages simply emit Frontcache's response headers and
`<fc:include/>` markers, and Frontcache does the rest. The same approach works for Python, Node,
Ruby or anything else that can set a response header.

```
Browser ──▶ Frontcache :9080 ──▶ Apache + PHP :80
```

**1. Get the Frontcache standalone server**

Two ways; pick either. Both are described in full in the
[install guide](../../docs/HOWTO-install.md).

*Container (nothing to install but Docker):*

```sh
docker run -d --name frontcache -p 9080:9080 \
  -e ORIGIN_HOST=host.docker.internal \
  pavlikovskiy/frontcache-server:2.6.0
```

*Archive:*

```sh
V=2.6.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V
curl -fLO $BASE/frontcache-server-$V.tar.gz
curl -fLO $BASE/frontcache-server-$V.tar.gz.sha256
# the published checksum may be a bare hash, so compare the hash field
# rather than using `shasum -c`, which needs the `hash  filename` form:
[ "$(shasum -a 256 frontcache-server-$V.tar.gz | cut -d' ' -f1)" \
  = "$(cut -d' ' -f1 < frontcache-server-$V.tar.gz.sha256)" ] && echo "checksum OK"
tar -xzf frontcache-server-$V.tar.gz
```

Add `-linux-x64` (or `-linux-aarch64`, `-macos-aarch64`) to the archive name for a build that
carries its own Java runtime, if you would rather not install a JDK 25.

**2. Point Frontcache at your PHP server**

In `FRONTCACHE_HOME/conf/frontcache.properties`:

```properties
front-cache.origin-host=localhost
front-cache.origin-http-port=80        # your Apache/nginx + PHP port
front-cache.origin-https-port=443

front-cache.default-domain=localhost
front-cache.site-key=CHANGE_ME

# the CLIENT-FACING ports - what redirect rewriting uses
front-cache.http-port=9080
front-cache.https-port=9443
```

**3. Deploy the PHP files**

Copy everything in this directory into your web server's `DocumentRoot`.

**4. Start Frontcache**

```sh
./frontcache-server-2.6.0/bin/frontcache        # listens on 9080
```

**5. Open it**

http://localhost:9080/

**What to look at**

Load the page **twice** and compare the `x-frontcache-trace-request.*` response headers. On the
first request every fragment is `dynamic`; on the second, `header.php` and `footer.php` come
`from-cache` while `user-profile.php` stays `dynamic`.

That difference is driven entirely from PHP. `index.php` opts the page into the cache with one
header:

```php
<?php header('x-frontcache-component-maxage: 1m'); ?>
```

and marks its fragments:

```html
<fc:include url="/header.php"/>
<fc:include url="/footer.php"/>
```

while `user-profile.php` sets `maxage: 0` (or is listed in `conf/dynamic-urls.conf`) so
per-user content is never cached. The full header set:

| Header | Meaning |
| --- | --- |
| `x-frontcache-component-maxage` | TTL. `0` = never cache, `-1`/`forever` = forever, or `60`/`15m`/`24h`. `bot:` / `browser:` prefixes give crawlers and users different TTLs |
| `x-frontcache-component-tags` | pipe-separated invalidation tags, e.g. `catalog\|product-42` |
| `x-frontcache-component-refresh` | `soft` = serve stale while revalidating |
| `x-frontcache-component-cache-level` | `L1` (memory) or `L2` (disk, default) |

**Invalidating from PHP**

The management API is plain HTTP, so no Java client is needed:

```sh
curl -s -H "x-frontcache-site-key: YOUR_SITE_KEY" \
  "http://localhost:9080/frontcache-io?action=invalidate&filter=/product-42.*"
```

Restrict that endpoint with `front-cache.management.port` and a firewall — anything that can
reach it with the site key can flush your cache.
