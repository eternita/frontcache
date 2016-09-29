package org.frontcache.hystrix.fr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCUtils;
import org.frontcache.core.StringUtils;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.FallbackLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileBasedFallbackResolver implements FallbackResolver {

	private final static String FALLBACKS_CONFIG_FILE = "fallbacks.conf";

	private static final String CONTENT_TYPE_DEFAULT = "text/html";
	
	private static Logger logger = LoggerFactory.getLogger(FileBasedFallbackResolver.class);
	
	private static Logger fallbackLogger = LoggerFactory.getLogger(FallbackLogger.class);

	private Map <String, Pattern> uri2patternMap = new LinkedHashMap<String, Pattern>();
	
	private Map<String, String> uri2fileMap = new LinkedHashMap<String, String>();

	private Map<String, String> file2fetchURLMap = new LinkedHashMap<String, String>();
	
	private HttpClient client;

	
	public FileBasedFallbackResolver() {
		loadFallbackConfig();
	}
	
	@Override
	public void init(HttpClient client) {
		this.client = client;
		populateFallbacksFromOrigin();
	}

	@Override
	public List<FallbackConfigEntry> getFallbackConfigs() {
		
		List<FallbackConfigEntry> list = new ArrayList<FallbackConfigEntry>();
		
		for (String uri : uri2fileMap.keySet())
		{
			FallbackConfigEntry fce = new FallbackConfigEntry();
			
			fce.setUrlPattern(uri);
			String fileName = uri2fileMap.get(uri);
			fce.setFileName(fileName);
			fce.setInitUrl(file2fetchURLMap.get(fileName));
			
			list.add(fce);
		}
		
		
		return list;
	}
	
	@Override
	public WebResponse getFallback(String urlStr)
	{
		
		String currentFallbackURLpattern = null;
		for (String fallbackPattern : uri2fileMap.keySet())
		{
			Pattern p = uri2patternMap.get(fallbackPattern);
			
    		if (p.matcher(urlStr).matches())
    		{
    			currentFallbackURLpattern = fallbackPattern;
    			break;
    		}
		}
		
		if (null == currentFallbackURLpattern)
		{
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " default | URL doesn't match any pattern | " + urlStr);
			return getDefalut(urlStr);
		}
		
		String fileName = uri2fileMap.get(currentFallbackURLpattern);
		
		if (null == fileName)
		{
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " default | no file for pattern " + currentFallbackURLpattern + " | " + urlStr);
			return getDefalut(urlStr);
		}

		WebResponse webResponse = getFallbackFromFile(urlStr, fileName);
		
		if (null == webResponse){
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " default | can't read from file " + fileName + " | " + urlStr);
			return getDefalut(urlStr);
		}
		
		fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " from file | " + fileName + " | " + urlStr);
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
		File fallbackDataFile = new File(new File(frontcacheHome), "fallbacks/" + fileName);
		if (fallbackDataFile.exists())
		{
			InputStream is = null;
			
			try {
				is = new FileInputStream(fallbackDataFile);
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
		String contentType = CONTENT_TYPE_DEFAULT;
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
		String contentType = CONTENT_TYPE_DEFAULT;
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
	

	// for empty files with defined URIs
	private void populateFallbacksFromOrigin()
	{
		String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		
		for (String fileName : file2fetchURLMap.keySet())
		{
			
			File fallbackDataFile = new File(new File(frontcacheHome), "fallbacks/" + fileName);
			if (fallbackDataFile.exists())
				continue;
			
			
			String fetchURL = file2fetchURLMap.get(fileName);
			
			// get content from URL
			HttpResponse response = null;

			try {
				HttpHost httpHost = FCUtils.getHttpHost(new URL(fetchURL));
				HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(fetchURL));

				response = client.execute(httpHost, httpRequest);
				
				int httpResponseCode = response.getStatusLine().getStatusCode();
				
				if (httpResponseCode < 200 || httpResponseCode > 299)
					continue;
				
				OutputStream fos = new FileOutputStream(fallbackDataFile);
				
				Header contentTypeHeader = response.getFirstHeader("Content-Type");
				if (null != contentTypeHeader)
				{
					String contentType = contentTypeHeader.getValue();
					fos.write(("Content-Type:" + contentType + "\n").getBytes());
				}
				
				// save response to file
				
				InputStream is = response.getEntity().getContent();
				try {
					int bytesRead = 0;
		            int bufferSize = 4000;
			         byte[] byteBuffer = new byte[bufferSize];				
			         while ((bytesRead = is.read(byteBuffer)) != -1) {
			             fos.write(byteBuffer, 0, bytesRead);
			         }
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try
					{
						fos.flush();
						fos.close();
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}

			} catch (Exception ioe) {
				ioe.printStackTrace();
			} finally {
				if (null != response)
				{
					try {
						((CloseableHttpResponse) response).close();
					} catch (IOException e) {
						e.printStackTrace();
					} 
				}
			}
			
		} // for (String fileName : file2fetchURLMap.keySet())
		return;
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
			fallbackURLPattern = arr[1];
			fetchURL = arr[2];
			
			file2fetchURLMap.put(fileName, fetchURL);
		} else {
			logger.error("Can't parse fallback string (should be 2 or 3 words): " + fallbackStr);
			return;
		}

		uri2patternMap.put(fallbackURLPattern, Pattern.compile(fallbackURLPattern));
		uri2fileMap.put(fallbackURLPattern, fileName);

		logger.debug("Fallback loaded: " + fallbackStr);
		
	}

}
