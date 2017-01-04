package org.frontcache.hystrix.fr;

public class FallbackConfigEntry {

	protected String fileName;
	protected String urlPattern;
	protected String initUrl;
	
	public FallbackConfigEntry() { // for JSON mapper
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getUrlPattern() {
		return urlPattern;
	}

	public void setUrlPattern(String urlPattern) {
		this.urlPattern = urlPattern;
	}

	public String getInitUrl() {
		return initUrl;
	}

	public void setInitUrl(String initUrl) {
		this.initUrl = initUrl;
	}

}
