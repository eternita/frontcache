# Frontcache with PHP
## Update settings
Update settings in /frontcache-server/FRONTCACHE_HOME/conf/frontcache.properties
    front-cache.http-port=9080
    front-cache.https-port=9443

    //your apache+php port
    front-cache.origin-http-port=80
    front-cache.origin-https-port=443

## Setup Apache and php
Copy files from `frontcache/examples/frontcache-php/` into  `DocumentRoot` 

## Start Frontcache server
run `/frontcache-server/bin/frontcache`

## Open in Browser
http://localhost:9080/
 


