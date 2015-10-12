package org.frontcache.wrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * 
 * Wrapper for response
 *
 */
public class HttpResponseWrapperImpl extends HttpServletResponseWrapper implements FrontCacheHttpResponseWrapper {
	int BUFFER_SIZE = 4000;
	private StringWriter sw = new StringWriter(BUFFER_SIZE);

	public HttpResponseWrapperImpl(HttpServletResponse response) {
		super(response);
	}

	public PrintWriter getWriter() throws IOException {
		return new PrintWriter(sw);
	}
	
	public String getContentString() {
		return sw.toString();
	}

}