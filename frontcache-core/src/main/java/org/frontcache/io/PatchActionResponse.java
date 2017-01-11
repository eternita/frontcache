package org.frontcache.io;

public class PatchActionResponse extends ActionResponse {

	
	public PatchActionResponse() { // for JSON mapper
		setAction("patch");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
}
