FROM logstash:5

RUN \
  apt-get clean all && \
  apt-get update && \
  apt-get install nano

RUN mkdir /opt/geoip
RUN mkdir /var/log/fc

COPY /conf/geoip/GeoLite2-City.mmdb /opt/geoip/GeoLite2-City.mmdb

#RUN logstash-plugin install logstash-filter-de_dot

COPY conf/fc-logstash.conf /etc/logstash/

CMD ["-f", "/etc/logstash/fc-logstash.conf"]