package org.frontcache.example;

import org.frontcache.FrontCacheFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only Frontcache-specific wiring this example needs: register FrontCacheFilter over the
 * cacheable URL surface.
 *
 * Everything else - the JSP view resolver, the dispatcher servlet, default servlet handling -
 * is left to Spring Boot's auto-configuration and two properties in application.properties.
 * (The pre-3.x version of this example configured all of it by hand and used @EnableWebMvc,
 * which actually switches that auto-configuration off.)
 */
@Configuration
public class WebConfig {

	/**
	 * Scope the URL patterns to what is safe to cache. Keep admin, login and POST endpoints
	 * out of it, or list them in FRONTCACHE_HOME/conf/dynamic-urls.conf.
	 */
	@Bean
	public FilterRegistrationBean<FrontCacheFilter> frontcacheFilter() {
		FilterRegistrationBean<FrontCacheFilter> registration =
				new FilterRegistrationBean<>(new FrontCacheFilter());
		registration.addUrlPatterns("/example/*");
		registration.setName("FrontCacheFilter");
		registration.setOrder(1);
		return registration;
	}
}
