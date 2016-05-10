
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

scp -i oregon_key.pem -r /Users/spa/git/frontcache/frontcache-coins/FRONTCACHE_HOME/conf ubuntu@or.coinshome.net:/opt/frontcache

scp -i oregon_key.pem -r /Users/spa/git/frontcache/frontcache-coins/build/libs ubuntu@or.coinshome.net:/opt/frontcache

fix cache storage in RONTCACHE_HOME/conf/fc-ehcache-config.xml

scp -i oregon_key.pem /Users/spa/git/frontcache/frontcache-coins/FRONTCACHE_HOME/conf/fclogs.xml ubuntu@or.coinshome.net:/opt/frontcache/fclogs.xml

scp -i oregon_key.pem -r /Users/spa/git/frontcache/warmer ubuntu@or.coinshome.net:/opt/frontcache

scp -i singapore_key.pem -r /Users/spa/git/frontcache/frontcache-coins/build/libs ubuntu@sg.coinshome.net:/opt/frontcache

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

http://localhost:8080/hystrix-dashboard/monitor/monitor.html?streams=%5B%7B%22name%22%3A%22%22%2C%22stream%22%3A%22http%3A%2F%2For.coinshome.net%2Fhystrix.stream%22%2C%22auth%22%3A%22%22%2C%22delay%22%3A%22%22%7D%2C%7B%22name%22%3A%22%22%2C%22stream%22%3A%22http%3A%2F%2Fsg.coinshome.net%2Fhystrix.stream%22%2C%22auth%22%3A%22%22%2C%22delay%22%3A%22%22%7D%5D

-Darchaius.configurationSource.additionalUrls=file:///Users/spa/git/frontcache/frontcache-coins/turbine/config.properties


http://or.coinshome.net/hystrix-dashboard/monitor/monitor.html?streams=%5B%7B%22name%22%3A%22%22%2C%22stream%22%3A%22http%3A%2F%2Fsg.coinshome.net%2Fhystrix.stream%22%2C%22auth%22%3A%22%22%2C%22delay%22%3A%22%22%7D%2C%7B%22name%22%3A%22%22%2C%22stream%22%3A%22http%3A%2F%2For.coinshome.net%2Fhystrix.stream%22%2C%22auth%22%3A%22%22%2C%22delay%22%3A%22%22%7D%2C%7B%22name%22%3A%22%22%2C%22stream%22%3A%22http%3A%2F%2Fdirect.coinshome.net%2Fhystrix.stream%22%2C%22auth%22%3A%22%22%2C%22delay%22%3A%22%22%7D%5D



