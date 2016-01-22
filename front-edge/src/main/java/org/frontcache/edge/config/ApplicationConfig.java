package org.frontcache.edge.config;

import org.frontcache.edge.CoinsRequestContext;
import org.frontcache.edge.filter.pre.FrontCachePreFilter;
import org.frontcache.edge.filter.route.ForwardFilter;
import org.frontcache.edge.filter.route.SimpleHostRoutingFilter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;



@Configuration
public class ApplicationConfig
{

    static
    {
        RequestContext.setContextClass(CoinsRequestContext.class);
 
    }

    @Bean
    public BeanPostProcessor dispatcherServletBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DispatcherServlet) {
                    ((DispatcherServlet) bean).setDispatchOptionsRequest(true);
                    ((DispatcherServlet) bean).setDispatchTraceRequest(true);
                }
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                return bean;
            }
        };
    }
    
    @Bean
    public ZuulFilter routingFilter()
    {
        return new SimpleHostRoutingFilter();
    	//return new ForwardFilter();
    }
    
    @Bean
    public ZuulFilter cacheFilter()
    {
        return new FrontCachePreFilter();
    }
}
