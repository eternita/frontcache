##docker

Build all images

jjs -scripting build.js

or
 
./build.sh


Start images 
start_fc_elk.sh

Stop elk
stop_fc_elk.sh

### resize Docker.qcow2
qemu-img resize ~/Library/Containers/com.docker.docker/Data/com.docker.driver.amd64-linux/Docker.qcow2 +20G

###Remove all containers and images 

docker stop $(docker ps -a -q)
docker rm $(docker ps -a -q)
###ssh

docker exec -i -t <containerid>  /bin/bash
docker exec -it $container /bin/bash -c "export TERM=xterm; exec bash"

### check network
docker network inspect bridge

##logstash

copy logs 

scp fc:/opt/frontcache/logs/frontcache-requests-*.zip /tmp/frontcache/zips/

unzip 'zips/*.zip'  -d logs/


##elastic
http://localhost:9200/

get all indexes
curl 'localhost:9200/_cat/indices?v'

in kibana Dev Tools

DELETE /logstash-2016.12.27?pretty
GET /_cat/indices?v


## kibana

http://localhost:5601/

Import Dashboard
 * Open http://localhost:5601/app/kibana#/management?_g=()
 * Saved Objects
 * Import -> scripts/docker/fc-elk/kibana.json