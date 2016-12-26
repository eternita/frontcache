package org.frontcache.io;

import java.util.List;

public class GetBotsActionResponse extends ActionResponse {

	private List<String> bots;

	public GetBotsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
	public List<String> getBots() {
		return bots;
	}

	public void setBots(List<String> bots) {
		this.bots = bots;
	}
	
}
