docker build -t frontcache/logstash .


docker run -v /tmp/frontcache/logs:/var/log/fc  -p 5044:5044 -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload"  --name=frontcache-logstash frontcache/logstash

#docker run -v /tmp/frontcache/logs:/var/log/fc  -p 5044:5044 -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload"  --name=frontcache-logstash frontcache/logstash

