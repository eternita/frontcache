package org.frontcache.cache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.apache.http.client.HttpClient;
import org.frontcache.FCConfig;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.FC_ThroughCache;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = LoggerFactory.getLogger(getClass());
	
	private static final String BOT_CONIF_FILE = "bots.conf";
	
	private Set<String> botUserAgentKeywords = new LinkedHashSet<String>();
	
	private static final String[] NON_PERSISTENT_HEADERS = new String[]{
			"Set-Cookie", 
			"Date",
			FCHeaders.X_FRONTCACHE_ID,
			FCHeaders.X_FRONTCACHE_COMPONENT,
//			FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE,
//			FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, //  tags should not be filtered by FC (e.g. client -> fc2 (standalone) -> fc1 (filter) -> origin)
			FCHeaders.X_FRONTCACHE_REQUEST_ID,
			FCHeaders.X_FRONTCACHE_CLIENT_IP,
			FCHeaders.X_FRONTCACHE_DEBUG,
			FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE,
			FCHeaders.X_FRONTCACHE_DEBUG_CACHED,
			FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME,
			FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE
		};
	
	public abstract WebResponse getFromCacheImpl(String url);

	@Override
	public final WebResponse getFromCache(String url)
	{
		WebResponse cachedWebResponse = new FC_ThroughCache(this, url).execute();
		
		return cachedWebResponse;
	}


	@Override
	public WebResponse processRequest(String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;
		
		long lengthBytes = -1;
		
		String currentRequestURL = context.getCurrentRequestURL();
		
		WebResponse cachedWebResponse = new FC_ThroughCache(this, currentRequestURL).execute();
		
		// isDynamicForClientType depends on clientType (bot|browser) - maxAge="[bot|browser:]30d"
		// content is cached for bots and dynamic for browsers
		// when dynamic - don't update cache
		boolean isCacheableForClientType = true; // true - save/update to cache;  false - don't save/update to cache
		
		if (null != cachedWebResponse)
		{
			String clientType = getClientType(requestHeaders); // bot | browser
			Map<String, Long> expireTimeMap = cachedWebResponse.getExpireTimeMap();
		
			isCacheableForClientType = isWebComponentCacheableForClientType(expireTimeMap, clientType);
			
			if (isWebComponentExpired(expireTimeMap, clientType))
			{
				removeFromCache(currentRequestURL);
				cachedWebResponse = null; // refresh from origin
			}
		}

		if (!isCacheableForClientType || // call origin if request is dynamic for client type [bot|browser] or component is null
				null == cachedWebResponse)
		{
			try
			{
				cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client, context);
				lengthBytes = cachedWebResponse.getContentLenth();

				// save to cache
				if (isCacheableForClientType && cachedWebResponse.isCacheable())
				{
					WebResponse copy4cache = cachedWebResponse.copy();
					Map<String, List<String>> copyHeaders = copy4cache.getHeaders(); 
					for (String removeKey : NON_PERSISTENT_HEADERS)
						copyHeaders.remove(removeKey);
					
					copy4cache.setUrl(currentRequestURL);
					putToCache(currentRequestURL, copy4cache); // put to cache copy
				}
			} catch (FrontCacheException ex) {
				throw ex;
			} catch (Exception ex) {
				ex.printStackTrace();
				throw new FrontCacheException(ex);
			}
				
		} else {
			
			cachedWebResponse = cachedWebResponse.copy(); //to avoid modification instance in cache
			isCached = true;
			lengthBytes = cachedWebResponse.getContentLenth();			
		}
		
		
		RequestLogger.logRequest(currentRequestURL, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);
		
		return cachedWebResponse;
	}	
	
	/**
	 * Check with current time if expired
	 *  
	 * @param clientType {bot | browser}
	 * @return
	 */
	private boolean isWebComponentExpired(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMap must not be empty for clientType=" + clientType);
			return true; 
		}
		
		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return true; 
		}
		
		if (CacheProcessor.CACHE_FOREVER == expireTimeMillis)
			return false;
		
		if (System.currentTimeMillis() > expireTimeMillis)
			return true;
		
		return false;
	}

	// true - save to cache
	private boolean isWebComponentCacheableForClientType(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMap must not be empty for clientType=" + clientType);
			return false; 
		}
		
		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return false; 			
		}
		
		if (CacheProcessor.NO_CACHE == expireTimeMillis)
			return false;
		
		return true;
	}
	
	
	private String getClientType(Map<String, List<String>> requestHeaders)
	{		
		
		if (null != requestHeaders.get("User-Agent"))
		{
			for (String userAgent : requestHeaders.get("User-Agent"))
				if (isBot(userAgent))
					return FCHeaders.REQUEST_CLIENT_TYPE_BOT;
		}
		return FCHeaders.REQUEST_CLIENT_TYPE_BROWSER;
	}
	
	private boolean isBot(String userAgent)
	{
		for (String botKeyword : botUserAgentKeywords)
			if (userAgent.contains(botKeyword))
				return true;
			
		return false;
	}
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = new HashMap<String, String>();
		status.put("impl", this.getClass().getName());

		return status;
	}
	


	@Override
	public void init(Properties properties) {		
		Objects.requireNonNull(properties, "Properties should not be null");
		
		logger.info("Loading list of bots from " + BOT_CONIF_FILE);
		BufferedReader confReader = null;
		InputStream is = null;
				
		try 
		{
			is = FCConfig.getConfigInputStream(BOT_CONIF_FILE);
			if (null == is)
			{
				logger.info("List of bots is not loaded from " + BOT_CONIF_FILE);
				return;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String botStr;
			int botConfigCounter = 0;
			while ((botStr = confReader.readLine()) != null) {
				if (botStr.trim().startsWith("#")) // handle comments
					continue;
				
				if (0 == botStr.trim().length()) // skip empty
					continue;
				
				botUserAgentKeywords.add(botStr);
				botConfigCounter++;
			}
			logger.info("Successfully loaded " + botConfigCounter +  " User-Agent keywords for bots");					
			
		} catch (Exception e) {
			logger.info("List of bots is not loaded from " + BOT_CONIF_FILE, e);
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

}
