#!/usr/bin/env bash

./fc-elk/start_elk.sh
./fc-logstash/start_logstash.sh
./fc-logstash/get_logs.sh