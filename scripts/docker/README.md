##docker

Build all images

jjs -scripting build.js

or
 
./build.sh


Start images 
start_fc_elk.sh

Stop elk
stop_fc_elk.sh


##logstash

copy logs 

scp fc:/opt/frontcache/logs/frontcache-requests-*.zip /tmp/frontcache/zips/

unzip 'zips/*.zip'  -d logs/


##elastic
get all indexes
curl 'localhost:9200/_cat/indices?v'

in kibana Dev Tools

DELETE /logstash-2016.12.27?pretty
GET /_cat/indices?v