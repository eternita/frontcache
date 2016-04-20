
## FrontCache - utility to increase Web App performance.

### [it's page fragment cache + remote includes + concurrent execution]

It's developed & tested with Java based Web apps but can be used with other languages/technologies as well. 

### Features

1. Allows to cache parts of dynamic pages.

2. Faster execution even for pure dynamic pages.
   Standard MVC approach runs backend routines in serial mode.
   FrontCache execute page components / fragments in cun concurrent mode what can speed up server response time in multiple times.
   
3. Much more storage efficient caching. 
   Standard caching of whole pages cache a lot of duplicated content (e.g. footer and header for every page).
   FrontCache utilizes component caching what allows to avoid caching duplicates. 

==
###Technical introduction

It's implemented as Servlet Filter.
Every page can have 
```
<fc:component /> tag which has chaching directives applied to the page:
<fc:component maxage="0" /> - do not cache (defalut)
<fc:component maxage="-1" /> - cache forever
<fc:component maxage="forever" /> - cache forever
<fc:component maxage="60" /> - cache for 60 seconds
<fc:component maxage="60s" /> - cache for 60 seconds
<fc:component maxage="15m" /> - cache for 15 minutes
<fc:component maxage="24h" /> - cache for 24 hours
```
The same in case of using HTTP Headers:
```
X-frontcache.component.maxage = 0  - do not cache (defalut)
X-frontcache.component.maxage = -1 - cache forever
X-frontcache.component.maxage = forever - cache forever
X-frontcache.component.maxage = 60 - cache for 60 seconds
X-frontcache.component.maxage = 60s - cache for 60 seconds
X-frontcache.component.maxage = 15m - cache for 15 minutes
X-frontcache.component.maxage = 24h - cache for 24 hours
```

```
<fc:include /> tag which specifies URL for data to be included:
<fc:include url="/mysite/include-header" /> - this include will make HTTP call to the current webb app with URL  '/mysite/include-header'
<fc:include url="/store/include-product-details-${productId}" /> - this include will make HTTP call to the current web app with URL '/store/include-product-details-${productId}' where ${productId} is a parameter in request scope.
```

Every included data can have caching tag / directives what enables effective page fragment / component caching.
All includes for the same page are run concurrently what allows to speed up a lot comparing to serail call inside standard MVC controller.

==

FrontCache supports couple caching implementations:
- build-in in-memory cache 
- ehCache adapter - see http://www.ehcache.org/ for details
- Redis adapter - see http://redis.io/ for details
 

### Request log : (frontcache-requests.log)
 isCacheable (1 - true, 0 - false), isCached  (1 - true, 0 - false), response time (in ms), response size (in bytes)
 
### Debuging requests 
Set http request header 
```
X-frontcache.debug = true
```
HTTP Response will have following headers with debug info:

```
X-frontcache.debug.cacheable - isCacheable (true, false)
X-frontcache.debug.cached - isCached  (true, false)
X-frontcache.debug.response-time - response time (in ms)
X-frontcache.debug.response-size - response size (in bytes)
```

### Development environment setup

1. checkout

2. add/update gradle.properties to ${CHECKOUT_DIR}/frontcache

and set there path to local maven repo - e.g.

mavenLocalRepo = file:///Users/spa/.m2/repository

3. create simbol link for standalone tests (to run the same as filter tests)

```
ln -s ${CHECKOUT_DIR}/frontcache/frontcache-filter-tests/src/main/webapp/common ${CHECKOUT_DIR}/frontcache/frontcache-standalone-tests/src/main/webapp
on my laptop it looks like this:
ln -s /Users/spa/git/frontcache/frontcache-filter-tests/src/main/webapp/common /Users/spa/git/frontcache/frontcache-standalone-tests/src/main/webapp

```
4. build

```
./gradlew clean build
```
 
 
### More / better descriotion is comming soon. 
Dig in fc-mvc-demo project if you want it earlier. fc-mvc-demo project has the same page implemented using standard MVC approach and using FrontCache.
