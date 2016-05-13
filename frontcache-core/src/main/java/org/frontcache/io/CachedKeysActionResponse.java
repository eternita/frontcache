package org.frontcache.io;

import java.util.List;

public class CachedKeysActionResponse extends ActionResponse {

	private List<String> cachedKeys;
	
	public CachedKeysActionResponse(List<String> cachedKeys) {
		setAction("cached keys");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.cachedKeys = cachedKeys;
	}

	public List<String> getCachedKeys() {
		return cachedKeys;
	}

	public void setCachedKeys(List<String> cachedKeys) {
		this.cachedKeys = cachedKeys;
	}


}
