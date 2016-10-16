## Frontcache standalone web app


# deployment

create home dir 
mkdir /opt/frontcache

chown ubuntu /opt/frontcache 

add following line to catalina.sh

-Dfrontcache.home=/opt/frontcache
-Dlogback.configurationFile=/opt/frontcache/conf/fc-logback.xml
