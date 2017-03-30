FROM tomcat:8-jre8

ENV DEPLOYMENT_DIR /usr/local/tomcat/webapps
RUN mkdir /opt/frontcache
ENV FRONTCACHE_HOME /opt/frontcache

COPY dist/FRONTCACHE_HOME/ ${FRONTCACHE_HOME}
RUN rm -R ${DEPLOYMENT_DIR}/*/
RUN sed -i 's/8080/9080/g' /usr/local/tomcat/conf/server.xml

COPY dist/frontcache-console.war ${DEPLOYMENT_DIR}/console.war
COPY dist/frontcache-server.war ${DEPLOYMENT_DIR}/ROOT.war

EXPOSE 9080