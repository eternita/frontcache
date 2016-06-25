package org.frontcache.io;

public class ReloadActionResponse extends ActionResponse {

	
	public ReloadActionResponse() { // for JSON mapper
		setAction("Frontcache edge has been reloaded");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
}
