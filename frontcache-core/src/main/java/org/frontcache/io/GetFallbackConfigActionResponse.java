package org.frontcache.io;

import java.util.Map;
import java.util.Set;

import org.frontcache.hystrix.fr.FallbackConfigEntry;

public class GetFallbackConfigActionResponse extends ActionResponse {

	private Map <String, Set<FallbackConfigEntry>> fallbackConfigs;
	
	public GetFallbackConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Map<String, Set<FallbackConfigEntry>> getFallbackConfigs() {
		return fallbackConfigs;
	}

	public void setFallbackConfigs(Map<String, Set<FallbackConfigEntry>> fallbackConfigs) {
		this.fallbackConfigs = fallbackConfigs;
	}

}
