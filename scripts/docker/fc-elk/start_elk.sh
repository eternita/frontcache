#!/bin/bash


CONTAINER_ID=`docker ps -a -f name=frontcache-elk -q`
echo $CONTAINER_ID

if [ -n "$CONTAINER_ID" ]; then
    echo "Container exists."

    RUNNING_CONTAINER_ID=`docker ps -f name=frontcache-elk -q`
    
    if [ -n "$RUNNING_CONTAINER_ID" ]; then
      echo "Container is already running. ID: $RUNNING_CONTAINER_ID"
    else
      echo "Container exists but stopped. Starting container... $RUNNING_CONTAINER_ID"    
      docker start $CONTAINER_ID    
    fi
  
else

  echo "ELK container does not exists.. Running container"
  docker run --memory="8g" -v /tmp/frontcache/logs:/var/log/fc   -p 5601:5601 -p 9200:9200 -e ES_HEAP_SIZE="2g" -e LOGSTASH_START=0  --name=frontcache-elk frontcache/elk
fi