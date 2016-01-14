package org.frontcache.cache;

import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.FrontCacheClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;
import org.frontcache.reqlog.RequestLogger;

public class NoCacheProcessor implements CacheProcessor {

	protected Logger logger = Logger.getLogger(getClass().getName());

	@Override
	public WebResponse processRequest(String originUrlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isRequestDynamic = true;
		long lengthBytes = -1;
		
		WebResponse cachedWebComponent = null;
		try
		{
			cachedWebComponent = FCUtils.dynamicCall(originUrlStr, requestHeaders, client);
			lengthBytes = cachedWebComponent.getContentLenth();
		} catch (Exception ex)
		{
			ex.printStackTrace();
		}

		RequestLogger.logRequest(originUrlStr, isRequestCacheable, isRequestDynamic, System.currentTimeMillis() - start, lengthBytes);

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
	public FrontCacheClient getFrontCacheClient() {
		return new NoCacheClient();
	}

}

/**
 * 
 * 
 *
 */
class NoCacheClient extends FrontCacheClient {

	public void remove(String filter)
	{		
	}
	
	public void removeAll()
	{		
	}
	
}

