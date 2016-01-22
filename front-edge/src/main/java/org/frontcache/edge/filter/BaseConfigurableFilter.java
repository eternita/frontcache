package org.frontcache.edge.filter;

import org.springframework.core.Ordered;

import com.netflix.zuul.ZuulFilter;

public abstract class BaseConfigurableFilter extends ZuulFilter implements Ordered {

	private int order = Ordered.HIGHEST_PRECEDENCE;

	public BaseConfigurableFilter() {

	}

	@Override
	public int getOrder() {
		return order;
	}

	@Override
	public int filterOrder() {
		return this.getOrder();
	}

	@Override
	public boolean shouldFilter() {
		return true;
	}
}
