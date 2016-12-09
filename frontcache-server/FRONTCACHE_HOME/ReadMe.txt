Steps to start Frontcache in standalone mode:

Pre-requirements:
Java 1.8, Apache Tomcat (or other Servlet container)

export environment FRONTCACHE_HOME variable pointed to extracted frontcache directory: 
  for example 
  export FRONTCACHE_HOME=/Users/spa/opt/frontcache-1.0.0

edit ${FRONTCACHE_HOME}/conf/frontcache.properties 
  (set front-cache.origin-host=en.wikipedia.org) 

copy binaries to Tomcat
  copy ${FRONTCACHE_HOME}/bin/ROOT.war to ${TOMCAT_HOME}/webapps
  copy ${FRONTCACHE_HOME}/bin/frontcache-console.war to ${TOMCAT_HOME}/webapps
                    
start tomcat

It's ready:
  Point browser to http://localhost:9080/
    You can see some content from www.coinshome.net (or other site you pointed in 'front-cache.origin-host' variable) 
  Point browser to http://localhost:9080/frontcache-console/
    You can see Frontcache Console with real-time data and configs 
     



more info: http://www.frontcache.org/get-started