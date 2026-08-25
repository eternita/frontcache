# Frontcache in filter mode — configuration skeleton

This is a `FRONTCACHE_HOME` skeleton for **use case #1**: Frontcache as a servlet filter inside
your own Java web app, with no separate proxy tier. The app is its own origin.

```
conf/frontcache.properties     the file to edit first
conf/bots.conf                 user-agent keywords treated as bots
conf/dynamic-urls.conf         URL patterns that must NEVER be cached (cart, login, admin)
conf/fallbacks.conf            what to serve when a component fails or a circuit opens
conf/guard-rules.conf          checks applied before cache and origin
conf/fc-l1-ehcache-config.xml  L1 (in-memory) cache sizing
conf/resilience.properties     circuit-breaker thresholds and thread pools
conf/fc-logback.xml            request / error / fallback / rejected-request logs
fallbacks/                     fallback response bodies
cache/                         the L2 Lucene index (created at runtime)
logs/                          written at runtime
```

## 1. Add the dependency

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://repo.eternita.co/maven2' }
}
dependencies {
    implementation 'org.frontcache:frontcache-core:2.5.0'
}
```

Maven:

```xml
<repositories>
  <repository><id>eternita</id><url>https://repo.eternita.co/maven2</url></repository>
</repositories>
<dependency>
  <groupId>org.frontcache</groupId><artifactId>frontcache-core</artifactId><version>2.5.0</version>
</dependency>
```

Frontcache 2.5.0 is **Jakarta EE 10** (`jakarta.servlet`, Servlet 6.0) and Java 25 bytecode. It
will not load in a `javax.servlet` container or on an older JVM.

## 2. Register the filter

`WEB-INF/web.xml`:

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

Scope the `url-pattern` to the cacheable surface. Keep admin, login and POST endpoints out of
it, or list them in `conf/dynamic-urls.conf`.

Spring Boot, without a `web.xml`:

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

## 3. Edit `conf/frontcache.properties`

At minimum `front-cache.default-domain` and `front-cache.site-key`; both ship as `CHANGE_ME`.
Note there is no `front-cache.origin-host` in filter mode — see the comments in the file.

## 4. Launch the container with

```
-Dfrontcache.home=/path/to/FRONTCACHE_HOME
-Dlogback.configurationFile=/path/to/FRONTCACHE_HOME/conf/fc-logback.xml
```

## 5. Declare what is cacheable

Frontcache caches nothing until the origin says so. From a JSP, using the taglib
(`org.frontcache:frontcache-core` ships `fc.tld`):

```jsp
<%@ taglib uri="http://frontcache.org/core" prefix="fc" %>

<fc:component maxage="1h" tags="catalog|product" refresh="soft" level="L2" />

<fc:include url="/example/header" />
<fc:include url="/example/user-profile" client="browser" />
```

Or set the headers directly from a controller:

```
x-frontcache-component-maxage: 1h          # 0 = no cache, -1/forever = forever; bot:/browser: prefixes allowed
x-frontcache-component-tags: catalog|product-42
x-frontcache-component-refresh: soft       # serve stale while revalidating
x-frontcache-component-cache-level: L2     # L1 (memory) or L2 (disk, default)
```

## 6. Invalidate on content change

```java
FrontCacheAgent agent = new FrontCacheAgent("http://localhost:8080");
agent.removeFromCache(siteKey, "/example/product-42.*");   // regexp
```

`org.frontcache:frontcache-agent` is a thin httpclient-only jar for app code; in-process you can
use `FrontCacheClient` from core instead. For several nodes, `FrontCacheAgentCluster` fans the
invalidation out to all of them.

## Verify

Request a mapped URL twice. With `front-cache.log-to-headers=true` the response carries
`x-frontcache-id` plus per-fragment timing headers, and on the second request the cacheable
fragments should report as coming from cache. Logs are under `logs/`.

---

Licensing: https://www.eternita.co/frontcache-license.html
Docs: https://github.com/eternita/frontcache/wiki
