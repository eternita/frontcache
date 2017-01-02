docker build -t frontcache/elk .

docker run --memory="8g" -v /tmp/frontcache/logs:/var/log/fc   -p 5601:5601 -p 9200:9200 -e ES_HEAP_SIZE="2g" -e LOGSTASH_START=0  --name=frontcache-elk frontcache/elk
#docker run --memory="8g" -v /tmp/frontcache/logs:/var/log/fc   -p 5601:5601 -p 9200:9200 -p 5044:5044 -e ES_HEAP_SIZE="2g" -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload"  --name=frontcache-elk frontcache/elk

#sudo docker run -v /tmp/frontcache/logs:/var/fc/logs  -p 5601:5601 -p 9200:9200 -p 5044:5044 -it -e ES_HEAP_SIZE="2g" -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload" --name=frontcache-elk  frontcache/elk