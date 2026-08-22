

* Add following environment variable (e.g. to catalina.sh in case of Tomcat)
```
-Dorg.frontcache.console.config=/opt/frontcache/conf/frontcache-console.properties
```


* Run Frontcache console (in Jetty container) with FRONTCACHE_CONFIG environment variable
```
FRONTCACHE_CONFIG=/path/to/frontcache-console.conf ./gradlew clean :frontcache-console:appRun
```
