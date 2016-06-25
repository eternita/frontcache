package org.frontcache.io;

import java.util.Map;

public class KeysDumpActionResponse extends ActionResponse {

	
	public KeysDumpActionResponse() { // for JSON mapper
		
	}
	
	public KeysDumpActionResponse(Map<String, String> cacheStatus) {
		setAction("key dump started - will be saved to ./warmer dir");
		setResponseStatus(RESPONSE_STATUS_OK);
	}

}
