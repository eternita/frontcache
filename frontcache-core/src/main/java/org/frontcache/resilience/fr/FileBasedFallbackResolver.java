/*
 * Copyright 2015 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.resilience.fr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.frontcache.core.FcHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.frontcache.FCConfig;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.StringUtils;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.FallbackLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileBasedFallbackResolver implements FallbackResolver {

	private final static String FALLBACKS_CONFIG_FILE = "fallbacks.conf";

	private static final String CONTENT_TYPE_DEFAULT = "text/html";

	// Fallbacks are a degraded response served when origin/circuit fails.
	// They must NOT be cached by any downstream cache (browser, CDN, e.g. Cloudflare) -
	// otherwise a transient origin failure gets pinned at the edge for the cached URL's TTL.
	// 503 keeps CDNs from caching the error; the no-store headers forbid it explicitly.
	public static final int FALLBACK_HTTP_STATUS = 503; // Service Unavailable
	public static final String FALLBACK_CACHE_CONTROL = "no-store, no-cache, max-age=0, must-revalidate";

	private static Logger logger = LoggerFactory.getLogger(FileBasedFallbackResolver.class);

	private static Logger fallbackLogger = FallbackLogger.logger();

//	# record description
//	# URI pattern it serves | file with data located in fallbacks dir | request for data (optional)

	// URI pattern -> fallback, in conf/fallbacks.conf order (first match wins)
	private Set<FallbackConfigEntryImpl> fallbackConfigs = new LinkedHashSet<FallbackConfigEntryImpl>();

	private FcHttpClient client;


	public FileBasedFallbackResolver() {
		loadFallbackConfig();
	}

	@Override
	public void init(FcHttpClient client) {
		this.client = client;
		populateFallbacksFromOrigin();
	}

	/**
	 * A copy, not the live entries: these go out over the management API, and the returned objects are
	 * plain {@link FallbackConfigEntry} rather than the {@link FallbackConfigEntryImpl} used internally
	 * so the compiled {@code Pattern} does not have to serialize.
	 *
	 * LinkedHashSet because the order is the matching order - first match wins - which is what makes
	 * the console's list readable as the rule sequence it actually is.
	 */
	@Override
	public Set<FallbackConfigEntry> getFallbackEntries() {

		Set<FallbackConfigEntry> configs = new LinkedHashSet<FallbackConfigEntry>();
		for (FallbackConfigEntryImpl fallbackConfigOriginal : fallbackConfigs)
		{
			FallbackConfigEntry fallbackConfig = new FallbackConfigEntry();
			fallbackConfig.setFileName(fallbackConfigOriginal.getFileName());
			fallbackConfig.setInitUrl(fallbackConfigOriginal.getInitUrl());
			fallbackConfig.setUrlPattern(fallbackConfigOriginal.getUrlPattern());
			configs.add(fallbackConfig);
		}

		return configs;
	}

	@Override
	public WebResponse getFallback(String fallbackSource, String urlStr)
	{
		FallbackConfigEntryImpl currentFallbackConfigEntry = null;

		for (FallbackConfigEntryImpl fallbackConfigEntry : fallbackConfigs)
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
			return getDefault(fallbackSource, urlStr);
		}


		String fileName = currentFallbackConfigEntry.fileName;

		if (null == fileName)
		{
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + "| default | no file for pattern " + currentFallbackConfigEntry.urlPattern + " | " + urlStr);
			return getDefault(fallbackSource, urlStr);
		}

		WebResponse webResponse = getFallbackFromFile(urlStr, fileName);

		if (null == webResponse){
			fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + "| default | can't read from file " + fileName + " | " + urlStr);
			return getDefault(fallbackSource, urlStr);
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
		markNonCacheable(webResponse);

		return webResponse;
	}

	/**
	 * Mark a fallback response so no downstream cache (browser, CDN) retains it.
	 * Sets a 503 status and explicit no-store cache headers.
	 *
	 * @param webResponse fallback response to stamp
	 */
	private void markNonCacheable(WebResponse webResponse)
	{
		webResponse.setStatusCode(FALLBACK_HTTP_STATUS);
		webResponse.addHeader(FCHeaders.CACHE_CONTROL, FALLBACK_CACHE_CONTROL);
		webResponse.addHeader(FCHeaders.PRAGMA, "no-cache");
	}

	/**
	 *
	 * @param urlStr
	 * @return
	 */
	private WebResponse getDefault(String fallbackSource, String urlStr)
	{
		byte[] outContentBody = (fallbackSource + ": Default Fallback for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody);
		webResponse.addHeader(FCHeaders.CONTENT_TYPE, CONTENT_TYPE_DEFAULT);
		markNonCacheable(webResponse);

		return webResponse;
	}


	// for empty files with defined URIs
	private void populateFallbacksFromOrigin()
	{
		String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		for (FallbackConfigEntryImpl fallbackConfig : fallbackConfigs)
		{
			String fileName = fallbackConfig.getFileName();

			File fallbackDataFile = new File(new File(frontcacheHome), "fallbacks/" + fileName);
			if (fallbackDataFile.exists())
				continue;

			fallbackDataFile.getParentFile().mkdirs(); // create dirs when needed

			String fetchURL = fallbackConfig.getInitUrl(); // init URL is optional -> skip if missed
			if (null == fetchURL)
				continue;

			// Fetch through FcHttpClient. This used to be a hand-rolled execute() -> getStatusLine()
			// -> getFirstHeader() -> buffer-copy-to-file block with three nested try/finally levels;
			// fetch(url) returns a WebResponse that already carries status, headers and body. The
			// bodies are fallback pages fetched once at startup, so materialising them is free.
			try {
				WebResponse fallbackResponse = client.fetch(fetchURL);

				int httpResponseCode = fallbackResponse.getStatusCode();
				if (httpResponseCode < 200 || httpResponseCode > 299)
					continue;

				try (OutputStream fos = new FileOutputStream(fallbackDataFile)) {

					String contentType = fallbackResponse.getHeader(FCHeaders.CONTENT_TYPE);
					if (null != contentType)
						fos.write((FCHeaders.CONTENT_TYPE + ":" + contentType + "\n").getBytes(StandardCharsets.UTF_8));

					byte[] content = fallbackResponse.getContent();
					if (null != content)
						fos.write(content);
				}

			} catch (Exception e) {
				logger.error("Can't load fallback for " + fetchURL, e);
			}
		} // for (FallbackConfigEntryImpl fallbackConfig : fallbackConfigs)

		return;
	}

	/**
	 *
	 */
	private void loadFallbackConfig() {
		fallbackConfigs.clear();

		logger.info("Starting fallback configs loading ...");
		Set<FallbackConfigEntryImpl> configs = loadFallbackConfig(FALLBACKS_CONFIG_FILE);
		if (null != configs)
		{
			fallbackConfigs.addAll(configs);
			logger.info("   loaded " + configs.size() + " fallbacks from " + FALLBACKS_CONFIG_FILE);
		} else {
			logger.info("   no fallbacks configs found in " + FALLBACKS_CONFIG_FILE + " - the built-in default fallback is used");
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
