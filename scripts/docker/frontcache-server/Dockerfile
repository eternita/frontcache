#FROM frontcache/tomcat8
FROM tomcat:8-jre8
ENV DEPLOYMENT_DIR /usr/local/tomcat/webapps
RUN mkdir /opt/frontcache
ENV FRONTCACHE_HOME /opt/frontcache
COPY FRONTCACHE_HOME/ ${FRONTCACHE_HOME}
RUN rm -R ${DEPLOYMENT_DIR}/*/
COPY dist/ROOT.war ${DEPLOYMENT_DIR}
EXPOSE 8080