package org.frontcache.io;

public class DummyActionResponse extends ActionResponse {

	public DummyActionResponse() {
		setAction("dummy");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
}
