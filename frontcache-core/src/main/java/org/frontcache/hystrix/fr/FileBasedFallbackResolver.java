/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCHeaders;
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

//	# record description
//	# URI pattern it serves | file with data located in fallbacks dir | request for data (optional)

	// Map <Domain, Map <URI patern, Pattern>>
	private Map <String, Set<FallbackConfigEntryImpl>> uri2fallbackMap = new LinkedHashMap<String, Set<FallbackConfigEntryImpl>>();
	
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
	public Map <String, Set<FallbackConfigEntry>> getFallbackConfigs() {
		
		Map <String, Set<FallbackConfigEntry>> configMap = new LinkedHashMap<String, Set<FallbackConfigEntry>>();
		for (String domain : uri2fallbackMap.keySet())
		{
			Set<FallbackConfigEntry> domainConfigs = new LinkedHashSet<FallbackConfigEntry>();
			for (FallbackConfigEntryImpl fallbackConfigOriginal : uri2fallbackMap.get(domain))
			{
				FallbackConfigEntry fallbackConfig = new FallbackConfigEntry();
				fallbackConfig.setFileName(fallbackConfigOriginal.getFileName());
				fallbackConfig.setInitUrl(fallbackConfigOriginal.getInitUrl());
				fallbackConfig.setUrlPattern(fallbackConfigOriginal.getUrlPattern());
				domainConfigs.add(fallbackConfig);
			}
			
			configMap.put(domain, domainConfigs);
		}
		return configMap;
	}
	
	@Override
	public WebResponse getFallback(DomainContext domain, String fallbackSource, String urlStr)
	{
		String fallbackDomain = domain.getDomain();
		
		Set <FallbackConfigEntryImpl> fallbackConfig = uri2fallbackMap.get(domain.getDomain());
		
		if (null == fallbackConfig)
		{
			fallbackConfig = uri2fallbackMap.get(FCConfig.DEFAULT_DOMAIN);
			fallbackDomain = FCConfig.DEFAULT_DOMAIN;
		}
		
		FallbackConfigEntryImpl currentFallbackConfigEntry = null;
		
		for (FallbackConfigEntryImpl fallbackConfigEntry : fallbackConfig)
		{
    		if (fallbackConfigEntry.getUrlRegexpPattern().matcher(urlStr).matches())
    		{
    			currentFallbackConfigEntry = fallbackConfigEntry;
    			break;
    		}
		}
		if (null == currentFallbackConfigEntry)
		{
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + "| default | URL doesn't match any pattern | " + urlStr);
			return getDefalut(fallbackSource, urlStr);
		}
		
		
		String fileName = currentFallbackConfigEntry.fileName;
		if (fallbackDomain != FCConfig.DEFAULT_DOMAIN)
			fileName = fallbackDomain + "/" + fileName;
		
		if (null == fileName)
		{
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + "| default | no file for pattern " + currentFallbackConfigEntry.urlPattern + " | " + urlStr);
			return getDefalut(fallbackSource, urlStr);
		}

		WebResponse webResponse = getFallbackFromFile(urlStr, fileName);
		
		if (null == webResponse){
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + "| default | can't read from file " + fileName + " | " + urlStr);
			return getDefalut(fallbackSource, urlStr);
		}
		
		fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + "| from file | " + fileName + " | " + urlStr);
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
		String startStr = FCHeaders.CONTENT_TYPE + ":";
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
		
		WebResponse webResponse = new WebResponse(urlStr, fallbackContent.getBytes());
		
		webResponse.addHeader(FCHeaders.CONTENT_TYPE, contentType); 
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}

	/**
	 * 
	 * @param urlStr
	 * @return
	 */
	private WebResponse getDefalut(String fallbackSource, String urlStr)
	{
		byte[] outContentBody = (fallbackSource + ": Default Fallback for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody);
		webResponse.addHeader(FCHeaders.CONTENT_TYPE, CONTENT_TYPE_DEFAULT); 
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
	

	// for empty files with defined URIs
	private void populateFallbacksFromOrigin()
	{
		String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		for (String domain : uri2fallbackMap.keySet())
		{
			
			for (FallbackConfigEntryImpl fallbackConfig : uri2fallbackMap.get(domain))
			{
				String fileName = fallbackConfig.getFileName();
				if (!domain.equals(FCConfig.DEFAULT_DOMAIN))
					fileName = domain + "/" + fileName;
				
				File fallbackDataFile = new File(new File(frontcacheHome), "fallbacks/" + fileName);
				if (fallbackDataFile.exists())
					continue;
				
				fallbackDataFile.getParentFile().mkdirs(); // create dirs when needed
				
				String fetchURL = fallbackConfig.getInitUrl(); // init URL is optional -> skip if missed
				if (null == fetchURL)
					continue;
				
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
					
					Header contentTypeHeader = response.getFirstHeader(FCHeaders.CONTENT_TYPE);
					if (null != contentTypeHeader)
					{
						String contentType = contentTypeHeader.getValue();
						fos.write((FCHeaders.CONTENT_TYPE + ":" + contentType + "\n").getBytes());
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
			} // for (FallbackConfigEntryImpl fallbackConfig : uri2fallbackMap.get(domain))
		} // for (String domain : uri2fallbackMap.keySet())
		
		return;
	}
	
	/**
	 * 
	 */
	private void loadFallbackConfig() {
		uri2fallbackMap.clear();
		
		logger.info("Starting fallback configs loading ...");
		for (String domain : FCConfig.getDomains())
		{
			Set<FallbackConfigEntryImpl> fallbackConfigs = loadFallbackConfig(domain + "/" + FALLBACKS_CONFIG_FILE);
			
			if (null != fallbackConfigs)
			{
				uri2fallbackMap.put(domain, fallbackConfigs);
				logger.info("   " + domain + " -> loaded " + fallbackConfigs.size() + " fallbacks from domain configs");
			} else {
				logger.info("   " + domain + " -> no fallbacks configs found - use default configuration");
			}
		}
		
		// default bot configs
		Set<FallbackConfigEntryImpl> fallbackConfigs = loadFallbackConfig(FALLBACKS_CONFIG_FILE);
		if (null != fallbackConfigs)
		{
			uri2fallbackMap.put(FCConfig.DEFAULT_DOMAIN, fallbackConfigs);
			logger.info("   " + FCConfig.DEFAULT_DOMAIN + " -> loaded " + fallbackConfigs.size() + " fallbacks from domain configs");
		} else {
			logger.info("   " + FCConfig.DEFAULT_DOMAIN + " -> no fallbacks configs found - use default configuration");
		}
		logger.info("Fallback configs loading is completed ...");
		return;
	}
	
	/**
	 * 
	 */
	private Set<FallbackConfigEntryImpl> loadFallbackConfig(String file) {
		BufferedReader confReader = null;
		InputStream is = null;
		
		Set<FallbackConfigEntryImpl> fallbackConfigList = new LinkedHashSet<FallbackConfigEntryImpl>();
		
		try 
		{
			is = FCConfig.getConfigInputStream(file);
			if (null == is)
			{
				logger.error("Fallback configs are not loaded from " + file);
				return null;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String fallbackStr;
			while ((fallbackStr = confReader.readLine()) != null) {
				try {
					if (fallbackStr.trim().startsWith("#")) // handle comments
						continue;
					
					if (0 == fallbackStr.trim().length()) // skip empty
						continue;

					fallbackConfigList.add(loadFallbackConfigStr(fallbackStr));
					
				} catch (PatternSyntaxException ex) {
					logger.info("Fallback URI pattern - " + fallbackStr + " is not loaded");					
				}
			}
			
		} catch (Throwable e) {
			logger.info("Fallback configs are not loaded from " + file);
			return null;
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
		
		return fallbackConfigList;
	}
	
	private FallbackConfigEntryImpl loadFallbackConfigStr(String fallbackStr)
	{
		String fileName = null;
		String fetchURL = null;
		String fallbackURLPattern = null;
		String[] arr = fallbackStr.split(" ");
		if (arr.length == 2) {
			fallbackURLPattern = arr[0];
			fileName = arr[1];
		} else if (arr.length == 3) {
			fallbackURLPattern = arr[0];
			fileName = arr[1];
			fetchURL = arr[2];
			
//			file2fetchURLMap.put(fileName, fetchURL);
		} else {
			logger.error("Can't parse fallback string (should be 2 or 3 words): " + fallbackStr);
			return null;
		}

//		uri2patternMap.put(fallbackURLPattern, Pattern.compile(fallbackURLPattern));
//		uri2fileMap.put(fallbackURLPattern, fileName);

		logger.debug("Fallback loaded: " + fallbackStr);
		FallbackConfigEntryImpl fallbackConfig = new FallbackConfigEntryImpl(); 
		fallbackConfig.setFileName(fileName);
		fallbackConfig.setInitUrl(fetchURL);
		fallbackConfig.setUrlPattern(fallbackURLPattern);
		fallbackConfig.setUrlRegexpPattern(Pattern.compile(fallbackURLPattern));
		
		return fallbackConfig;
	}

}
