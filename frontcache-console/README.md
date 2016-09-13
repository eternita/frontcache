
* Configure nodes for console (frontcache-console.properties)

```
# format
#http://localhost:8080/
#https://www.example.com/

https://or.coinshome.net:443/
https://sg.coinshome.net:443/
https://origin.coinshome.net:443/
```


* Add following environment variable (e.g. to catalina.sh in case of Tomcat)
```
-Dorg.frontcache.console.config=/opt/frontcache/conf/frontcache-console.properties
```


* Run Frontcache console (in Jetty container)
```
./gradlew clean :frontcache-console:jettyRun
```
