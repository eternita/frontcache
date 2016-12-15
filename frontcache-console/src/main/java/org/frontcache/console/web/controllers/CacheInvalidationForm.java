package org.frontcache.console.web.controllers;

public class CacheInvalidationForm {

	private String edge;
	private String filter;
	
	public CacheInvalidationForm() {
	}

	public String getEdge() {
		return edge;
	}

	public void setEdge(String edge) {
		this.edge = edge;
	}

	public String getFilter() {
		return filter;
	}

	public void setFilter(String filter) {
		this.filter = filter;
	}
	
}
