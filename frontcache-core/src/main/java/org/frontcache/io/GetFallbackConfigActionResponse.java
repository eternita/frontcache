package org.frontcache.io;

import java.util.List;

import org.frontcache.hystrix.fr.FallbackConfigEntry;

public class GetFallbackConfigActionResponse extends ActionResponse {

	private List<FallbackConfigEntry> fallbackConfigs;
	
	public GetFallbackConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public List<FallbackConfigEntry> getFallbackConfigs() {
		return fallbackConfigs;
	}

	public void setFallbackConfigs(List<FallbackConfigEntry> fallbackConfigs) {
		this.fallbackConfigs = fallbackConfigs;
		setAction(fallbackConfigs.size() + " fallback configs found");
	}
	
}
