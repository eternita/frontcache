package org.frontcache.io;

public class DumpKeysActionResponse extends ActionResponse {

	
	public DumpKeysActionResponse() { // for JSON mapper
		setAction("dumping keys is started - will be saved to ./warmer dir");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
}
