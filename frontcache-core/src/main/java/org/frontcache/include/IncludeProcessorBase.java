package org.frontcache.include;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;
import java.util.logging.Logger;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.frontcache.FCUtils;
import org.frontcache.WebComponent;
import org.frontcache.cache.CacheProcessor;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public abstract class IncludeProcessorBase implements IncludeProcessor {

	
	protected Logger logger = Logger.getLogger(getClass().getName());

	protected static final String START_MARKER = "<fc:include";
	protected static final String END_MARKER = "/>";
	
	private static final String SET_COOKIE_SEPARATOR = "; ";
	private static final String COOKIE = "Cookie";

	protected CacheProcessor cacheProcessor;
	
	public IncludeProcessorBase() {
	}

	
	public void setCacheProcessor(CacheProcessor cacheProcessor)
	{
		this.cacheProcessor = cacheProcessor;
	}
	

	/**
	 * 
	 * @param content
	 * @return
	 */
	protected String getIncludeURL(String content)
	{
		logger.fine("include tag - " + content);
		final String START_MARKER = "url=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String urlValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.fine("include URL - " + urlValue);
				return urlValue;
			} else {
				// can't find closing 
				return null;
			}
			
			
		} else {
			// no url attribute
			return null;
		}

	}
	

	/**
	 * read cookies from original request and convert to string to be passed to further call (include)
	 * 
	 * @param httpRequest
	 * @return
	 */
	private String getCookies(HttpServletRequest httpRequest)
	{
        Cookie[] cookies = httpRequest.getCookies();
        if (null != cookies)
        {
    		StringBuffer cookieStringBuffer = new StringBuffer();

        	for (int i = 0; i < cookies.length; i++)
        	{
        		Cookie c = cookies[i];
        		if (null == c)
        			continue;
        		
				cookieStringBuffer.append(c.getName());
				cookieStringBuffer.append("=");
				cookieStringBuffer.append(c.getValue());
				if (i + 1 < cookies.length) // has next
					cookieStringBuffer.append(SET_COOKIE_SEPARATOR);
        	}
            return cookieStringBuffer.toString();
        }		
        
        return null;
	}

	protected String callInclude(String urlSrt, HttpServletRequest httpRequest)
    {

		// check if cache is ON and response is cached
		if (null != cacheProcessor)
		{
			WebComponent cachedWebComponent = cacheProcessor.getFromCache(urlSrt);
			if (null != cachedWebComponent)
				return cachedWebComponent.getContent();
		}
		
		
		// do dynamic call 
		int httpResponseCode = -1;
		String contentType = "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        InputStream is = null;
        try {
            URL u = new URL(urlSrt);
            HttpURLConnection uc = (HttpURLConnection) u.openConnection();

            // translate cookies
            String cookies = getCookies(httpRequest);
            if (null != cookies)
    			uc.setRequestProperty(COOKIE, cookies);
            
            
            is = uc.getInputStream();
            int bytesRead = 0;
            int bufferSize = 4000;
             byte[] byteBuffer = new byte[bufferSize];              
             while ((bytesRead = is.read(byteBuffer)) != -1) {
                 baos.write(byteBuffer, 0, bytesRead);
             }
             
             httpResponseCode = uc.getResponseCode();
             contentType = uc.getContentType();
        } catch (Exception e) {
        	e.printStackTrace();
            // TODO Auto-generated catch block
        } finally {
            if (null != is)
            {
                try {
                    is.close();
                } catch (IOException e) {
                	e.printStackTrace();
                    // TODO Auto-generated catch block
                }
            }
        }
		
        String dataStr = new String(baos.toByteArray());
        
        if (200 == httpResponseCode || 201 == httpResponseCode)
        {
        	// response is OK -> check if response is subject to cache
    		if (null != cacheProcessor)
    		{
    			WebComponent cachedWebComponent = FCUtils.parseWebComponent(dataStr);
    			if (cachedWebComponent.isCacheable())
    			{
    				cachedWebComponent.setContentType(contentType);
    				cacheProcessor.putToCache(urlSrt, cachedWebComponent);
    			}
    		}
        }
        
        return dataStr;
    }

	@Override
	public void init(Properties properties) {
	}

	@Override
	public void destroy() {
	}	
	
}
