#!/usr/bin/env bash

set -e

array=("fc2" "fc3" "fc4")

baseDir="/tmp/logs/"
distDir="/tmp/frontcache/logs"

if [ ! -d ${baseDir} ]; then
     echo "Creating base directory for logs..."
     mkdir -p $baseDir
fi

if [ ! -d ${distDir} ]; then
     echo "Creating logstash directory for logs..."
     mkdir -p $distDir
fi

# Verify rsync is available.
echo "Checking rsync..."

if ! rsync --version 1>/dev/null 2>&1; then
    echo "Error: rsync not found." 1>&2
    exit 1
fi
echo "Ok!"

# creating directories for logs
for i in "${array[@]}"
do
    logDir=$baseDir$i
    if [ ! -d ${logDir} ]; then
      echo "Creating directory $logDir"
      mkdir -p ${logDir}
    fi
   echo "Running rsync on server $i"    
   rsync -c  --rsync-path='sudo rsync'  $i:/opt/frontcache/logs/frontcache-requests*.zip $logDir
   unzip $logDir/\*.zip -d $logDir
   cd $logDir
   for FILENAME in *.log;
        
      do
      
      mv $FILENAME $i-$FILENAME; 
   done

   rsync -c $logDir/*.log  $distDir
   
   cd $logDir
   for FILENAME in *.log;
      do
      rm $FILENAME;
   done

done


#scp fc:/opt/frontcache/logs/frontcache-requests*.zip /tmp/frontcache/logs
#unzip /tmp/frontcache/logs/\*.zip -d /tmp/frontcache/logs