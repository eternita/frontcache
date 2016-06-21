package org.frontcache.hystrix.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileBasedFallbackResolver implements FallbackResolver {

	private final static String FALLBACKS_CONFIG_FILE = "fallbacks.conf";
	
	private static Logger logger = LoggerFactory.getLogger(FileBasedFallbackResolver.class);
	
	private Map <String, Pattern> uri2patternMap = new LinkedHashMap<String, Pattern>();
	
	private Map<String, String> uri2fileMap = new LinkedHashMap<String, String>();
	
	public FileBasedFallbackResolver() {
		loadFallbackConfig();
		
		populateFallbacksFromOrigin();
	}
	
	public WebResponse getFallback(String urlStr)
	{
		
		String currentFallbackURLpattern = null;
		for (String fallbackPattern : uri2fileMap.keySet())
		{
			Pattern p = uri2patternMap.get(fallbackPattern);
			
    		if (p.matcher(urlStr).find()) // TODO: URL or URI
    		{
    			currentFallbackURLpattern = fallbackPattern;
    			break;
    		}
		}
		
		if (null == currentFallbackURLpattern)
			return getDefalut(urlStr);
		
		String fileName = uri2fileMap.get(currentFallbackURLpattern);
		
		if (null == fileName)
			return getDefalut(urlStr);

		WebResponse webResponse = getFallbackFromFile(urlStr, fileName);
		
		if (null == webResponse)
			return getDefalut(urlStr);
		
		return webResponse;
	}
		

	private WebResponse getFallbackFromFile(String urlStr, String fileName)
	{
		byte[] outContentBody = ("TODO: read from file " + fileName).getBytes();
		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
	
	
	private WebResponse getDefalut(String urlStr)
	{
		byte[] outContentBody = ("Fallabck for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
	

	// for empty files with defined URIs
	private void populateFallbacksFromOrigin()
	{
		
	}
	
	/**
	 * 
	 */
	private void loadFallbackConfig() {
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FCConfig.getConfigInputStream(FALLBACKS_CONFIG_FILE);
			if (null == is)
			{
				logger.error("Fallback configs are not loaded from " + FALLBACKS_CONFIG_FILE);
				return;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String patternStr;
			int patternCounter = 0;
			while ((patternStr = confReader.readLine()) != null) {
				try {
					if (patternStr.trim().startsWith("#")) // handle comments
						continue;
					
					if (0 == patternStr.trim().length()) // skip empty
						continue;
					
//					uriIgnorePatterns.add(Pattern.compile(patternStr));
					patternCounter++;
				} catch (PatternSyntaxException ex) {
					logger.info("Fallback URI pattern - " + patternStr + " is not loaded");					
				}
			}
			logger.info("Successfully loaded " + patternCounter +  " callbacks");					
			
		} catch (Exception e) {
			logger.info("Fallback configs are not loaded from " + FALLBACKS_CONFIG_FILE);
		} finally {
			if (null != confReader)
			{
				try {
					confReader.close();
				} catch (IOException e) { }
			}
			if (null != is)
			{
				try {
					is.close();
				} catch (IOException e) { }
			}
		}
		
	}
	
}
