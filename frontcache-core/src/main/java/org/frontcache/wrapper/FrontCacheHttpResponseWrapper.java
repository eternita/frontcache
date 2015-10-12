package org.frontcache.wrapper;

import javax.servlet.http.HttpServletResponse;

public interface FrontCacheHttpResponseWrapper extends HttpServletResponse {

	public String getContentString();
	
	
}
