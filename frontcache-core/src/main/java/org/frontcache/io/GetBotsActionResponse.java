package org.frontcache.io;

import java.util.Map;
import java.util.Set;

public class GetBotsActionResponse extends ActionResponse {

	private Map<String, Set<String>> bots;

	public GetBotsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
	public Map<String, Set<String>> getBots() {
		return bots;
	}

	public void setBots(Map<String, Set<String>> bots) {
		this.bots = bots;
	}
	
}
