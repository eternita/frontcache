# Frontcache — JSP Tags

Concepts: [concept.md](concept.md) · Header equivalents: [http-headers.md](http-headers.md) · Batching includes: [include-combining.md](include-combining.md)

These tags manage Frontcache's behaviour from a Java app: a convenient way to set its HTTP
headers from a JSP rather than writing them by hand.

        <%@ taglib prefix="fc" uri="http://frontcache.org/core" %>

Tag | Description
--- | --- 
fc:component | sets caching strategy for page / include
fc:include | defines policy for processing server-side includes

## fc:component - setting caching strategy for page / include

        <fc:component maxage="bot:30d" tags="invalidation|tags" refresh="regular|soft" level="L1|L2" />

Attributes:

Attribute | Description
--- | --- 
**maxage** | amount of time page should be cached, optional, default - 0 (do not cache)
**tags** | invalidation tags used for page invalidation, optional
**refresh** | specifies refresh policy for the page after expiration time is reached, optional, default - regular
**level** | specifies cache level for cached requests, optional, default - L2


* **maxage** - amount of time page should be cached. Options:

        maxage="0" - do not cache
        maxage="-1" - cache forever
        maxage="60" - cache for 60 seconds
        maxage="60s" - cache for 60 seconds
        maxage="15m" - cache for 15 minutes
        maxage="24h" - cache for 24 hours
        maxage="30d" - cache for 30 days

Cache time can be client type specific. Page can be cached for bots and dynamic for browsers (or opposite).

        maxage="15m" - cache for 15 minutes for bots and browsers
        maxage="bot:15m" - cache for 15 minutes for bots and dynamic for browsers
        maxage="browser:15m" - cache for 15 minutes for browsers and dynamic for bots

* **tags** - invalidation tags used for page invalidation. Page can be removed from cache by URL or by invalidation tag. Attribute is optional.

* **refresh** - specifies refresh policy for the page after expiration time is reached. Attribute is optional. Default value is "regular". Options: 

        refresh="regular" - when cache get request for expired page it performs request to origin, puts fresh data to cache and returns fresh data to client.
        refresh="soft" - when cache get request for expired page it returns expired data from cache and performs background call to origin to refresh cache data.

* **level** - specifies cache level for cached requests. L1 - level 1 cache (in-memory) - fast and small. L2 - level 2 cache - large. L2 level is default. Attribute is optional. 

        level="L1" - page is stored in L1 cache. L1 cache is fast and small and stored in memory.
        level="L2" - page is stored in L2 cache (default). L2 cache is huge and stored on hard drive.

## fc:include - setting policy for processing server-side includes.

        <fc:include url="/example/include-page.jsp" client="all|browser|bot" call="sync|async" combine="true|group-name" />

Attributes:

Attribute | Description
--- | --- 
**url** | data to be included, mandatory
**client** | set if include is client type specific, optional, default value is "all"
**call** | set if include is executed synchronously or asynchronously, optional, default is "sync"
**combine** | set if the include may be batched with its siblings into one origin call, optional, default - not combinable

* **url** - data to be included. Attribute is mandatory.

* **client** - Set if include is client type specific. Options are "browser", "bot", "all". Attribute is optional, default value is "all".

        client="bot" include is performed for bots only
        client="browser" include is performed for browsers only
        client="all" include is performed for all client types (default)

* **call** - Set if include is executed synchronously or asynchronously. Options are "sync", "async". Attribute is optional, default value is "sync".

        call="sync" - include call is executed synchronously and include's data is inserted to the page.
        call="async" - include call is executed asynchronously, so no data is inserted to the page.

* **combine** - Set if this include may be batched with its siblings. When several combinable includes of one page miss the cache, the edge fetches them in a **single** origin request instead of one request each - so an origin that batches its data access pays its page-level query cost once. Attribute is optional; without it the include is fetched on its own, as before. Requires a handler at the origin: see [include-combining.md](include-combining.md).

        combine="true" - combinable, grouped with every other combine="true" include of the same URL path
        combine="search-cell" - combinable, grouped by path AND that name, so one endpoint can serve two sets on one page without merging them

Combining never applies to `call="async"` includes - nothing waits for them, so batching them buys nothing.
