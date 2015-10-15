package org.frontcache.include;

import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public interface IncludeProcessor {

	public void init(Properties properties);
	public void destroy();

	public String processIncludes(String content, String appOriginBaseURL, HttpServletRequest httpRequest);

}
