package org.frontcache.io;

public class InvalidateActionResponse extends ActionResponse {

	private String filter;
	
	public InvalidateActionResponse(String filter) {
		setAction("invalidate");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.filter = filter;
	}

	public String getFilter() {
		return filter;
	}

	public void setFilter(String filter) {
		this.filter = filter;
	}
	
}
