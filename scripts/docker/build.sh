#!/bin/bash

array=("fc-debian" "fc-java" "fc-tomcat8" "frontcache-server" "fc-elk")
for f in "${array[@]}"
do
echo "Building image $f"
cd $f
./build.sh
cd ..
done

