package org.frontcache;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorManager;
import org.frontcache.reqlog.RequestLogger;

public class FrontCacheServlet extends HttpServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private String appOriginBaseURL = FCConfig.getProperty("app_origin_base_url");
	
	private final String UTF8 = "UTF-8";
	
	private IncludeProcessor includeProcessor = null;
	
	private CacheProcessor cacheProcessor = null; // can be null (no caching)

	protected Logger logger = Logger.getLogger(getClass().getName());
	
	/**
	 * 
	 */
	public FrontCacheServlet() {
	}
	
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		cacheProcessor = CacheManager.getInstance();
		
		includeProcessor = IncludeProcessorManager.getInstance();
			
		includeProcessor.setCacheProcessor(cacheProcessor);
		
		return;
	}



	@Override
	protected void doGet(HttpServletRequest httpRequest, HttpServletResponse resp) throws ServletException, IOException 
	{

		String requestURLWithoutHostPort = FCUtils.getRequestURLWithoutHostPort(httpRequest); 
		String requestURL = appOriginBaseURL + requestURLWithoutHostPort; 
			
		String acceptHeader = httpRequest.getHeader("Accept");
//		logger.info(requestURL);
		
		String content = null;
		if (null != cacheProcessor && (null != acceptHeader && -1 < acceptHeader.indexOf("text")))
		{
			content = cacheProcessor.processCacheableRequest(httpRequest, resp, requestURL);
		} else {
			long start = System.currentTimeMillis();
			boolean isRequestDynamic = true;

			long lengthBytes = -1;

			// do dynamic call 
			Map<String, Object> respMap = FCUtils.dynamicCall(requestURL, httpRequest);
	        content = (String) respMap.get("dataStr");
			String contentType = (String) respMap.get("contentType");
	        String contentLenghtStr = (String) respMap.get("Content-Length");
	        if (null != contentLenghtStr)
	        	lengthBytes = Long.parseLong(contentLenghtStr);

	        if ((null != content)
				&& (null != contentType && -1 < contentType.indexOf("text")))
			{
				// remove custom component tag from response string
				WebComponent webComponent = FCUtils.parseWebComponent(requestURL, content);
				content = webComponent.getContent();
				lengthBytes = 2*content.length();
			}

			RequestLogger.logRequest(requestURL, isRequestDynamic, System.currentTimeMillis() - start, lengthBytes);
			
		}

		if (null != content)
		{
			content = includeProcessor.processIncludes(content, appOriginBaseURL, httpRequest);
			resp.getOutputStream().write(content.getBytes());
		}
		return;
	}


	@Override
	public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
		// TODO Auto-generated method stub
		
		// TODO handle GET - otherwise
		// log error
		// redirect to error page !!!???? 
		super.service(req, res);
	}


	
}
