package org.frontcache.io;

import java.util.List;

public class GetDynamicURLsActionResponse extends ActionResponse {

	private List<String> dynamicURLs;

	public GetDynamicURLsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public List<String> getDynamicURLs() {
		return dynamicURLs;
	}

	public void setDynamicURLs(List<String> dynamicURLs) {
		this.dynamicURLs = dynamicURLs;
	}
	
}
