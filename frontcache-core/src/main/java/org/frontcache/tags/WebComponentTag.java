package org.frontcache.tags;

@SuppressWarnings("serial")
public class WebComponentTag extends WebComponentSupport {

	public void setMaxage(String maxage) {
		super.maxage = maxage;
	}

	public void setTags(String tags) {
		super.tags = tags;
	}
	
	public void setRefresh(String refresh) {
		super.refresh = refresh;
	}
	
}