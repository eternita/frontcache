package org.frontcache.cache;

import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.WebComponent;
import org.frontcache.core.FCUtils;
import org.frontcache.reqlog.RequestLogger;

public class NoCacheProcessor implements CacheProcessor {

	protected Logger logger = Logger.getLogger(getClass().getName());

	@Override
	public WebComponent processRequest(String originUrlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws Exception {

		long start = System.currentTimeMillis();
		boolean isRequestDynamic = true;
		long lengthBytes = -1;
		
		WebComponent cachedWebComponent = null;
		try
		{
			cachedWebComponent = FCUtils.dynamicCall(originUrlStr, requestHeaders, client);
			lengthBytes = cachedWebComponent.getContentLenth();
		} catch (Exception ex)
		{
			ex.printStackTrace();
		}

		RequestLogger.logRequest(originUrlStr, isRequestDynamic, System.currentTimeMillis() - start, lengthBytes);

		return cachedWebComponent;
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
	public void putToCache(String url, WebComponent component) {
		// TODO Auto-generated method stub

	}

	@Override
	public WebComponent getFromCache(String url) {
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

}
