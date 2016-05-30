
# configure nodes for console (frontcache-console.properties)

```
# format
#http://localhost:8080/
#https://www.example.com/

https://or.coinshome.net:443/
https://sg.coinshome.net:443/
https://origin.coinshome.net:443/
```


add following line to catalina.sh
-Dorg.frontcache.console.config=/opt/frontcache/conf/frontcache-console.properties

