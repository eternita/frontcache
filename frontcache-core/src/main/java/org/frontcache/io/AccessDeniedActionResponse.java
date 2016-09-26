package org.frontcache.io;

public class AccessDeniedActionResponse extends ActionResponse {

	public AccessDeniedActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_ERROR);
		setErrorDescription("Access denied to Management URI on this port.");
	}
	
	public AccessDeniedActionResponse(String scheme) {
		setResponseStatus(RESPONSE_STATUS_ERROR);
		setErrorDescription("Access denied to Management URI on this port. Use port defined in connector with scheme=" + scheme);
	}
}
