package org.frontcache.io;

import java.util.Map;

public class CacheStatusActionResponse extends ActionResponse {

	private Map<String, String> cacheStatus;
	
	public CacheStatusActionResponse() { // for JSON mapper
		
	}
	
	public CacheStatusActionResponse(Map<String, String> cacheStatus) {
		setAction("cache status");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.cacheStatus = cacheStatus;
	}

	public Map<String, String> getCacheStatus() {
		return cacheStatus;
	}

	public void setCacheStatus(Map<String, String> cacheStatus) {
		this.cacheStatus = cacheStatus;
	}

}
