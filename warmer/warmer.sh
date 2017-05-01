#!/bin/bash

# put line number to start from
start_line=0

counter=0

while IFS='' read -r line || [[ -n "$line" ]]; do
 
  counter=$((counter+1))
  if [ $counter -gt $start_line ]
  then
    echo "crawling $line"
    curl -H "Accept: text/html" -H "Accept-Encoding: gzip, deflate" -H "User-Agent: Googlebot" -o output.log $line
  fi 
  
done < "$1"
