package org.frontcache.io;

import java.util.Map;

public class HelpActionResponse extends ActionResponse {

	private Map<String, String> actionsDescription = null;
	
	public HelpActionResponse() {
		setAction("help");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
	public HelpActionResponse(Map<String, String> actionsDescription) {
		this();
		this.actionsDescription = actionsDescription;
	}
	
	public Map<String, String> getActionsDescription() {
		return actionsDescription;
	}

}
