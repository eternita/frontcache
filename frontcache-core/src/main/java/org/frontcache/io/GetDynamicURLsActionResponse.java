package org.frontcache.io;

import java.util.Map;
import java.util.Set;

public class GetDynamicURLsActionResponse extends ActionResponse {

	private Map<String, Set<String>> dynamicURLs;

	public GetDynamicURLsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Map<String, Set<String>> getDynamicURLs() {
		return dynamicURLs;
	}

	public void setDynamicURLs(Map<String, Set<String>> dynamicURLs) {
		this.dynamicURLs = dynamicURLs;
	}
	
}
