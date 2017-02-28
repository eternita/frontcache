package org.frontcache.console.model;

import java.util.Map;
import java.util.Set;

import org.frontcache.hystrix.fr.FallbackConfigEntry;

public class FallbackConfig {

	private String name;
	private Map <String, Set<FallbackConfigEntry>> config;
	
	public FallbackConfig(String name, Map <String, Set<FallbackConfigEntry>> config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public Map <String, Set<FallbackConfigEntry>> getConfig() {
		return config;
	}

}
