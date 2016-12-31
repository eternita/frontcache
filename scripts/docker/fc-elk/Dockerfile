FROM sebp/elk

RUN mkdir /opt/geoip
RUN mkdir /var/log/fc

#RUN rm -R /etc/logstash/conf.d/*/
COPY /conf/logstash.conf /etc/logstash/conf.d/03-fclog.conf
COPY /conf/geoip/GeoLite2-City.mmdb /opt/geoip/GeoLite2-City.mmdb
#COPY /conf/logs/*  /var/log/fc/