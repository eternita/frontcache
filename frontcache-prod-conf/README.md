
## configuration for www.coinshome.net

run it (sudo because of port 80)
sudo ./gradlew clean :frontcache-coins:run 

to run in debug mode use:
sudo ./gradlew -DDEBUG=true clean :frontcache-coins:run

open browser at 
http://localhost:80/en/welcome.htm


# deployment

create home dir 
mkdir /opt/frontcache

chown ubuntu /opt/frontcache 

add following line to catalina.sh
-Dlogback.configurationFile=/opt/frontcache/conf/fc-logback.xml


scp -i oregon_key.pem -r /Users/spa/git/frontcache/frontcache-prod-conf/FRONTCACHE_HOME/conf ubuntu@or.coinshome.net:/opt/frontcache

scp -i oregon_key.pem -r /Users/spa/git/frontcache/frontcache-prod-conf/build/libs ubuntu@or.coinshome.net:/opt/frontcache

fix cache storage in RONTCACHE_HOME/conf/fc-ehcache-config.xml

scp -i oregon_key.pem /Users/spa/git/frontcache/frontcache-prod-conf/FRONTCACHE_HOME/conf/fclogs.xml ubuntu@or.coinshome.net:/opt/frontcache/fclogs.xml

scp -i oregon_key.pem -r /Users/spa/git/frontcache/warmer ubuntu@or.coinshome.net:/opt/frontcache

scp -i singapore_key.pem -r /Users/spa/git/frontcache/frontcache-prod-conf/build/libs ubuntu@sg.coinshome.net:/opt/frontcache

--
scp -i oregon_key.pem -r /Users/spa/git/Hystrix/hystrix-dashboard/build/libs/hystrix-dashboard.war ubuntu@or.coinshome.net:/opt/frontcache

# get cache status

http://or.coinshome.net/frontcache-io?action=get-cache-state
http://sg.coinshome.net/frontcache-io?action=get-cache-state
http://direct.coinshome.net/frontcache-io?action=get-cache-state

#invalidate abcd1234_key
http://or.coinshome.net/frontcache-io?action=invalidate&filter=abcd1234_key

#invalidate all
http://or.coinshome.net/frontcache-io?action=invalidate&filter=*

http://localhost:8080/turbine-web/turbine.stream?cluster=fc_cluster
http://or.coinshome.net/hystrix.stream
http://sg.coinshome.net/hystrix.stream


# warmer
scp -i singapore_key.pem -r /Users/spa/tmp1/_cached_keys_coinshome.net_20160530.txt ubuntu@sg.coinshome.net:/opt/frontcache/warmer/_cached_keys_coinshome.net_20160530.txt

scp -i oregon_key.pem -r /Users/spa/git/frontcache/warmer ubuntu@or.coinshome.net:/opt/frontcache/

scp -i oregon_key.pem /Users/spa/tmp1/_cached_keys_coinshome.net_20160529.txt ubuntu@or.coinshome.net:/opt/frontcache/warmer/_cached_keys_coinshome.net_20160529.txt


# frontcache console

scp -i oregon_key.pem -r /Users/spa/git/frontcache/frontcache-console/build/libs ubuntu@or.coinshome.net:/opt/frontcache
scp -i oregon_key.pem /Users/spa/git/frontcache/frontcache-prod-conf/FRONTCACHE_HOME/conf/frontcache-console.properties ubuntu@or.coinshome.net:/opt/frontcache/conf/frontcache-console.properties

add following line to catalina.sh
-Dorg.frontcache.console.config=/opt/frontcache/conf/frontcache-console.properties



