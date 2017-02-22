docker build -t frontcache/logstash .


docker run -v /tmp/frontcache/logs:/var/log/fc  -p 5044:5044 -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload"  --name=frontcache-logstash frontcache/logstash

#docker run -v /tmp/frontcache/logs:/var/log/fc  -p 5044:5044 -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload"  --name=frontcache-logstash frontcache/logstash


## Getting logs

update you ssh config file  ~/.ssh/config
add configuration for all cache servers
ex:

Host fc4 fc4.ch.net
Hostname fc4.ch.net
User ubuntu
IdentityFile ~/Documents/keys/singapore_key.pem

