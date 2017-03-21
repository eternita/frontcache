FROM tomcat:8-jre8
ENV FC_VERSION 1.2.0
ENV DEPLOYMENT_DIR /usr/local/tomcat/webapps
RUN mkdir /opt/frontcache
ENV FRONTCACHE_HOME /opt/frontcache

COPY FRONTCACHE_HOME/ ${FRONTCACHE_HOME}
RUN rm -R ${DEPLOYMENT_DIR}/*/

RUN curl -o frontcache-dist.zip -L http://static.frontcache.io/download/frontcache/frontcache-${FC_VERSION}/frontcache-${FC_VERSION}.zip
RUN unzip frontcache-dist.zip -d ${FRONTCACHE_HOME}

RUN cp  ${FRONTCACHE_HOME}/frontcache-${FC_VERSION}/dist/frontcache-console-${FC_VERSION}.war ${DEPLOYMENT_DIR}/console.war
RUN cp  ${FRONTCACHE_HOME}/frontcache-${FC_VERSION}/dist/frontcache-server-${FC_VERSION}.war ${DEPLOYMENT_DIR}/ROOT.war

EXPOSE 8080