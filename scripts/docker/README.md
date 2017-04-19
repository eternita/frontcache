## Building images

Build all images

`jjs -scripting build.js`
or
`./build.sh`


## [Running Frontcache standalone server](https://github.com/eternita/frontcache/tree/master/scripts/docker/frontcache-server "Running Frontcache standalone server")


## Running Kibana with frontcache logs

### Running ELK container
* Build image with `fc-elk/build.sh`
* Start Kibana & Elasticsearch with `fc-elk/start_elk.sh`
### Running Logstash container
* Build image with `fc-logstash/build.sh`
* Start Logstash with `fc-logstash/start_logstash.sh`
### Loading frontcache logs
* update `fc-logstash/get_logs.sh` to use your hosts. ex. array=("fc2" "fc3" "fc4")
* run `fc-logstash/get_logs.sh` to download logs
### Import Kibana Dashboard
 * Open http://localhost:5601/app/kibana#/management?_g=()
 * Saved Objects
 * Import -> scripts/docker/fc-elk/kibana.json
 
 
