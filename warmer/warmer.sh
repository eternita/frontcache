#!/bin/bash

while IFS='' read -r line || [[ -n "$line" ]]; do
 echo "crawling $line"
 curl -H "Accept: text/html" -H "Accept-Encoding: gzip, deflate" -H "user-agent: Googlebot" -o output.log $line
done < "$1"
