package org.frontcache;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.core.RequestContext;

public class FrontCacheServlet extends HttpServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	protected Logger logger = Logger.getLogger(getClass().getName());
	
	FrontCacheEngine fcEngine = null;
	
	/**
	 * 
	 */
	public FrontCacheServlet() {
	}
	
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		fcEngine = new FrontCacheEngine();
		
		return;
	}

	@Override
	public void destroy() {
		super.destroy();
		fcEngine.stop();
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
