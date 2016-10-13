package org.frontcache.io;

public class AccessDeniedActionResponse extends ActionResponse {

	public AccessDeniedActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_ERROR);
		setErrorDescription("Access denied to Management URI on this port.");
	}
	
	public AccessDeniedActionResponse(int port) {
		setResponseStatus(RESPONSE_STATUS_ERROR);
		setErrorDescription("Access denied to Management URI on this port. Use following port: " + port);
	}
}
