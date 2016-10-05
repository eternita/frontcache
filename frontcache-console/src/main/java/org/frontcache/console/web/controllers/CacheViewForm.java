package org.frontcache.console.web.controllers;

import org.frontcache.core.WebResponse;

public class CacheViewForm {

	private String edge;
	private String key;
	private String webResponseStr;
	
//	private WebResponse webResponse;
	
	public CacheViewForm() {
		// TODO Auto-generated constructor stub
	}

	public String getEdge() {
		return edge;
	}

	public void setEdge(String edge) {
		this.edge = edge;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getWebResponseStr() {
		return webResponseStr;
	}

	public void setWebResponseStr(String webResponseStr) {
		this.webResponseStr = webResponseStr;
	}


	
}
