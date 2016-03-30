
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

