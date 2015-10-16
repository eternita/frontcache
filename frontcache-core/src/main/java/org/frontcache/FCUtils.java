package org.frontcache;

import java.util.logging.Logger;

import org.frontcache.cache.CacheProcessor;

public class FCUtils {

	private FCUtils() {
	}
	
	private static Logger logger = Logger.getLogger(FCUtils.class.getName());

	/**
	 * wrap String to WebComponent.
	 * Check for header - extract caching options.
	 * 
	 * @param content
	 * @return
	 */
	public static final WebComponent parseWebComponent (String content)
	{
		WebComponent component = new WebComponent();
		
		int cacheMaxAgeSec = CacheProcessor.DEFAULT_CACHE_MAX_AGE;
		
		String outStr = null;
		final String START_MARKER = "<fc:component";
		final String END_MARKER = "/>";
		
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf(END_MARKER, startIdx);
			if (-1 < endIdx)
			{
				String includeTagStr = content.substring(startIdx, endIdx + END_MARKER.length());
				cacheMaxAgeSec = getCacheMaxAge(includeTagStr);
				
				
				// exclude tag from content
				outStr = content.substring(0, startIdx)   +   
						 content.substring(endIdx + END_MARKER.length(), content.length());
				
			} else {
				// can't find closing 
				outStr = content;
			}
			
		} else {
			outStr = content;
		}

		component.setContent(outStr);
		component.setCacheMaxAge(cacheMaxAgeSec);
		
		return component;
	}
	
	/**
	 * 
	 * @param content
	 * @return
	 */
	private static int getCacheMaxAge(String content)
	{
		logger.info("component tag - " + content);
		final String START_MARKER = "cache-max-age=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String maxAgeStr = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.info("component cache-max-age - " + maxAgeStr);
				try
				{
					return Integer.parseInt(maxAgeStr);
				} catch (Exception e) {
					logger.warning("can't parse component cache-max-age - " + maxAgeStr + " defalut is used (" + CacheProcessor.DEFAULT_CACHE_MAX_AGE + ")");
					return CacheProcessor.DEFAULT_CACHE_MAX_AGE;
				}
				
			} else {
				// can't find closing 
				return CacheProcessor.DEFAULT_CACHE_MAX_AGE;
			}
			
			
		} else {
			// no cache-max-age attribute
			return CacheProcessor.DEFAULT_CACHE_MAX_AGE;
		}

	}	
	

}
