package org.frontcache;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.core.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	protected Logger logger = LoggerFactory.getLogger(getClass());
	
	FrontCacheEngine fcEngine = null;
	
	public FrontCacheServlet() {
	}
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		fcEngine = FrontCacheEngine.getFrontCache();
		
		return;
	}

	@Override
	public void destroy() {
		super.destroy();
		FrontCacheEngine.destroy();
		fcEngine = null;
	}	


	@Override
	public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
        try {
        	fcEngine.init((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse);
        	
            fcEngine.processRequest();
        } catch (Throwable e) {
        	e.printStackTrace();
        	// TODO: handle error
        } finally {
            RequestContext.getCurrentContext().unset();
        }
	}

	
}
