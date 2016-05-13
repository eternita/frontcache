package org.frontcache.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoCacheProcessor implements CacheProcessor {

	private Logger logger = LoggerFactory.getLogger(getClass());

	@Override
	public WebResponse processRequest(String originUrlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;
		long lengthBytes = -1;
		
		WebResponse cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client, context);

		lengthBytes = cachedWebResponse.getContentLenth();

		RequestLogger.logRequest(originUrlStr, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);

		return cachedWebResponse;
	}

	@Override
	public void init(Properties properties) {
		// TODO Auto-generated method stub

	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void putToCache(String url, WebResponse component) {
		// TODO Auto-generated method stub

	}

	@Override
	public WebResponse getFromCache(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeFromCache(String filter) {
		// TODO Auto-generated method stub

	}

	@Override
	public void removeFromCacheAll() {
		// TODO Auto-generated method stub

	}

	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = new HashMap<String, String>();
		status.put("impl", this.getClass().getName());

		return status;
	}

	@Override
	public List<String> getCachedKeys() {
		return new ArrayList<String>();
	}

}



