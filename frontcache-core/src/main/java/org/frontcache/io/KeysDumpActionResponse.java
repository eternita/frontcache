package org.frontcache.io;

public class KeysDumpActionResponse extends ActionResponse {

	
	public KeysDumpActionResponse() { // for JSON mapper
		setAction("key dump started - will be saved to ./warmer dir");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
}
