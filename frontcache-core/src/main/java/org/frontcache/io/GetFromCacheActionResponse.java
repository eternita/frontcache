package org.frontcache.io;

import org.frontcache.core.WebResponse;

public class GetFromCacheActionResponse extends ActionResponse {

	private String key;
	private WebResponse value;

	public GetFromCacheActionResponse() { // for json mapper
		
	}
	
	public GetFromCacheActionResponse(String key) {
		setAction("get from cache");
		setResponseStatus(RESPONSE_STATUS_ERROR);
		this.key = key;
	}
	
	public GetFromCacheActionResponse(String key, WebResponse value) {
		setAction("get from cache");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.key = key;
		this.value = value;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public WebResponse getValue() {
		return value;
	}

	public void setValue(WebResponse value) {
		this.value = value;
	}


}
