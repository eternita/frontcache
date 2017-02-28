package org.frontcache.console.model;

import java.util.Map;
import java.util.Set;

public class DynamicURLsConfig {

	private String name;
	private Map<String, Set<String>> config;
	
	public DynamicURLsConfig(String name, Map<String, Set<String>> config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public Map<String, Set<String>> getConfig() {
		return config;
	}

}
