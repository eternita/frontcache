package org.frontcache.io;

import java.util.Map;

public class ReloadActionResponse extends ActionResponse {

	
	public ReloadActionResponse() { // for JSON mapper
		
	}
	
	public ReloadActionResponse(Map<String, String> cacheStatus) {
		setAction("Frontcache edge has been reloaded");
		setResponseStatus(RESPONSE_STATUS_OK);
	}

}
