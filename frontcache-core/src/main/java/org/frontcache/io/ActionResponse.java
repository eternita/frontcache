package org.frontcache.io;

public class ActionResponse {

	public static final String RESPONSE_STATUS_OK = "OK";
	public static final String RESPONSE_STATUS_ERROR = "ERROR";
	
	private String responseStatus;
	
	private String action;
	
	public ActionResponse() {
	}

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getResponseStatus() {
		return responseStatus;
	}

	public void setResponseStatus(String responseStatus) {
		this.responseStatus = responseStatus;
	}


	
}
