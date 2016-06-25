package org.frontcache.io;

public class ReloadFallbacksActionResponse extends ActionResponse {

	
	public ReloadFallbacksActionResponse() { // for JSON mapper
		setAction("Frontcache fallbacks have been reloaded");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
}
