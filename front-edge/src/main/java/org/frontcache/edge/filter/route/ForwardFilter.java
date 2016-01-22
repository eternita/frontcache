package org.frontcache.edge.filter.route;

import org.frontcache.FrontCacheEngine;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;

public class ForwardFilter extends ZuulFilter {
	private FrontCacheEngine fcEngine;
	public ForwardFilter()
	{
		fcEngine = new FrontCacheEngine();
	}

	@Override
	public boolean shouldFilter() {
		return RequestContext.getCurrentContext().getRouteHost() != null
				&& RequestContext.getCurrentContext().sendZuulResponse();
	}

	@Override
	public Object run() {
		//fcEngine.forwardToOrigin();
		return null;
	}

	@Override
	public String filterType() {
		return "route";
	}

	@Override
	public int filterOrder() {
		return 100;
	}


}