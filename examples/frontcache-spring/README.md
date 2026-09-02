### 'frontcache-spring' - Frontcache as a Servlet Filter in Spring Boot ###

Example project path: ./examples/frontcache-spring

The same use case as [`frontcache-jsp`](../frontcache-jsp) — Frontcache running as a servlet
filter inside your own app, with no separate proxy tier — but wired up the Spring Boot way.

**Requirements**

- **JDK 25** — Frontcache 2.8.0 is Java 25 bytecode and Jakarta EE 10 (`jakarta.servlet`),
  which is why this example is on Spring Boot 3.x. Boot 2.x and earlier are `javax.servlet`
  and cannot load it.
- **Maven 3.6.3+**

**Steps to run**

        git clone https://github.com/eternita/frontcache.git
        cd frontcache/examples/frontcache-spring/
        mvn spring-boot:run

point browser to http://localhost:8080/example

**What to look at**

Request the page **twice** and compare the `x-frontcache-trace-request.*` response headers
(`front-cache.log-to-headers=true` in `FRONTCACHE_HOME/conf/frontcache.properties` turns them
on). On the first request every fragment is `dynamic`; on the second, `header` and `footer`
come `from-cache` in ~1 ms while `user-profile` stays `dynamic` — because
`WEB-INF/views/index.jsp` marks them differently:

```jsp
<fc:include url="/example/header" />          <!-- cacheable -->
<fc:include url="/example/user-profile" />    <!-- per-user, never cached -->
```

**How it is wired**

Only one thing in this project is Frontcache-specific — the filter registration in
[`WebConfig.java`](src/main/java/org/frontcache/example/WebConfig.java):

```java
@Bean
FilterRegistrationBean<FrontCacheFilter> frontcacheFilter() {
    var reg = new FilterRegistrationBean<>(new FrontCacheFilter());
    reg.addUrlPatterns("/example/*");        // scope this to what is safe to cache
    reg.setName("FrontCacheFilter");
    reg.setOrder(1);
    return reg;
}
```

Everything else is ordinary Spring Boot: JSP views resolved from two properties in
`application.properties`, and the dependency plus the Frontcache repository in `pom.xml`.

Frontcache reads its own configuration from `FRONTCACHE_HOME`, passed as a system property by
the `spring-boot-maven-plugin` config in `pom.xml`:

```
-Dfrontcache.home=.../FRONTCACHE_HOME
-Dlogback.configurationFile=.../FRONTCACHE_HOME/conf/fc-logback.xml
```

**Using this in your own app**

Add the dependency and the repository from `pom.xml`, copy the `FilterRegistrationBean` above,
and take a `FRONTCACHE_HOME` skeleton from
`org.frontcache:frontcache-core:2.8.0:home@zip` (it ships a filter-mode
`frontcache.properties` and a `README-FILTER.md`). See the
[install guide](../../docs/install-guide.md).
