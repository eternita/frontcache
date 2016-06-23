package org.frontcache.hystrix.fr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.StringUtils;
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
			
    		if (p.matcher(urlStr).find())
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
		

	/**
	 * 
	 * @param urlStr
	 * @param fileName
	 * @return
	 */
	private WebResponse getFallbackFromFile(String urlStr, String fileName)
	{
		
		String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		File fcConfig = new File(new File(frontcacheHome), "fallbacks/" + fileName);
		if (fcConfig.exists())
		{
			InputStream is = null;
			
			try {
				is = new FileInputStream(fcConfig);
				int bytesRead = 0;
	            int bufferSize = 4000;
		         byte[] byteBuffer = new byte[bufferSize];				
		         while ((bytesRead = is.read(byteBuffer)) != -1) {
		             baos.write(byteBuffer, 0, bytesRead);
		         }
			} catch (Exception e) {		
				logger.error("Can't read fallback data from file " + fileName, e);
			} finally {
				if (null != is)
					try {
						is.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
			}
		}
		
		byte[] byteArray = baos.toByteArray();
		
		String fallbackContent = null;
		try {
			fallbackContent = new String(byteArray, 0, byteArray.length, "UTF8");
		} catch (UnsupportedEncodingException e) {
			logger.error("Can't read fallback text from file " + fileName, e);
		}		
		
		if (null == fallbackContent)
			return null;
		
		String contentType = "text/html"; // default
		String startStr = "Content-Type:";
		String endStr = "\n";
		
/*		
 		Content-Type:text/html;charset=UTF-8
		Hi from custom fallback
		->
		contentType = "text/html;charset=UTF-8"
		fallbackContent = "Hi from custom fallback"
*/		
		if (fallbackContent.startsWith(startStr))
		{
			contentType = StringUtils.getStringBetween(fallbackContent, startStr, endStr);
			int idx = fallbackContent.indexOf(endStr) + endStr.length();
			fallbackContent = fallbackContent.substring(idx);
		}
		
		WebResponse webResponse = new WebResponse(urlStr, fallbackContent.getBytes(), CacheProcessor.NO_CACHE);
		
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}

	/**
	 * 
	 * @param urlStr
	 * @return
	 */
	private WebResponse getDefalut(String urlStr)
	{
		byte[] outContentBody = ("Default Fallback for " + urlStr).getBytes();

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
			String fallbackStr;
			int patternCounter = 0;
			while ((fallbackStr = confReader.readLine()) != null) {
				try {
					if (fallbackStr.trim().startsWith("#")) // handle comments
						continue;
					
					if (0 == fallbackStr.trim().length()) // skip empty
						continue;

					loadFallbackConfigStr(fallbackStr);
					
					patternCounter++;
				} catch (PatternSyntaxException ex) {
					logger.info("Fallback URI pattern - " + fallbackStr + " is not loaded");					
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
		
		return;
	}
	
	private void loadFallbackConfigStr(String fallbackStr)
	{
		String fileName = null;
		String fetchURL = null;
		String fallbackURLPattern = null;
		String[] arr = fallbackStr.split(" ");
		if (arr.length == 2) {
			fileName = arr[0];
			fallbackURLPattern = arr[1];
		} else if (arr.length == 3) {
			fileName = arr[0];
			fetchURL = arr[1];
			fallbackURLPattern = arr[2];
		} else {
			logger.error("Can't parse fallback string (should be 2 or 3 words): " + fallbackStr);
			return;
		}

		uri2patternMap.put(fallbackURLPattern, Pattern.compile(fallbackURLPattern));
		uri2fileMap.put(fallbackURLPattern, fileName);

		logger.debug("Fallback loaded: " + fallbackStr);
		
	}
	
}
