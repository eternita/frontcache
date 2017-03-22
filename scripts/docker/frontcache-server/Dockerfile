FROM tomcat:8-jre8

ENV DEPLOYMENT_DIR /usr/local/tomcat/webapps
RUN mkdir /opt/frontcache
ENV FRONTCACHE_HOME /opt/frontcache

COPY FRONTCACHE_HOME/ ${FRONTCACHE_HOME}
RUN rm -R ${DEPLOYMENT_DIR}/*/

COPY dist/frontcache-console.war ${DEPLOYMENT_DIR}/console.war
COPY dist/frontcache-server.war ${DEPLOYMENT_DIR}/ROOT.war

EXPOSE 8080