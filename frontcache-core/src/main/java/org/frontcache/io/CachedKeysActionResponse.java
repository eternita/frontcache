package org.frontcache.io;

import java.util.List;

public class CachedKeysActionResponse extends ActionResponse {

	private long amount;
	
	private List<String> cachedKeys;
	
	public CachedKeysActionResponse() { // for JSON mapper
	}

	public CachedKeysActionResponse(List<String> cachedKeys) {
		setAction("cached keys");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.cachedKeys = cachedKeys;
		if (null != cachedKeys)
			this.amount = cachedKeys.size();
	}

	public long getAmount() {
		return amount;
	}

	public void setAmount(long amount) {
		this.amount = amount;
	}

	public List<String> getCachedKeys() {
		return cachedKeys;
	}

	public void setCachedKeys(List<String> cachedKeys) {
		this.cachedKeys = cachedKeys;
	}


}
