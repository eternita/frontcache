package org.frontcache;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.core.RequestContext;


/**
 * 
 *
 */
public class FrontCacheFilter implements Filter {

	private FrontCacheEngine fcEngine = null;
	
	
	@Override
	public void init(FilterConfig arg0) throws ServletException {

		fcEngine = FrontCacheEngine.getFrontCache();
		
		return;
	}

	@Override
	public void destroy() {

		fcEngine = null;
		FrontCacheEngine.destroy();
		return;
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {

        try {
        	fcEngine.init((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse, chain);
        	
            fcEngine.processRequest();
        } catch (Throwable e) {
        	e.printStackTrace();
        	// TODO: handle error
        } finally {
            RequestContext.getCurrentContext().unset();
        }
		
		return;
	}

	
	
}

