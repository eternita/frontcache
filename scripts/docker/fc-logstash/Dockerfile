FROM logstash:5

RUN echo "deb http://packages.elastic.co/curator/4/debian stable main" >> /etc/apt/sources.list

RUN \
  apt-get clean all && \
  apt-get update && \
  apt-get install nano && \
  apt-get install elasticsearch-curator

RUN mkdir /opt/geoip
RUN mkdir /opt/curator

RUN mkdir /var/log/fc

COPY /conf/geoip/GeoLite2-City.mmdb /opt/geoip/GeoLite2-City.mmdb
COPY /conf/curator/curator.yml /opt/curator/curator.yml
COPY /conf/curator/actions.yml /opt/curator/actions.yml

#RUN logstash-plugin install logstash-filter-de_dot

COPY conf/fc-logstash.conf /etc/logstash/

CMD ["-f", "/etc/logstash/fc-logstash.conf"]