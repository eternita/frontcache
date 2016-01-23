package org.frontcache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.web.SpringBootServletInitializer;
import org.springframework.cloud.netflix.zuul.EnableZuulServer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@EnableZuulServer
@Configuration
@EnableAutoConfiguration

@ComponentScan({"org.frontcache.edge.config"})
public class Edge extends SpringBootServletInitializer
{

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(Edge.class);
    }

    public static void main(String[] args)
    {
        SpringApplication.run(Edge.class, args);
    }
}