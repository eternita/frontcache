package org.frontcache.hystrix.fr;

import java.util.regex.Pattern;

public class FallbackConfigEntryImpl extends FallbackConfigEntry {

	private Pattern urlRegexpPattern;
	
	public FallbackConfigEntryImpl() { // for JSON mapper
		super();
	}

	public Pattern getUrlRegexpPattern() {
		return urlRegexpPattern;
	}

	public void setUrlRegexpPattern(Pattern urlRegexpPattern) {
		this.urlRegexpPattern = urlRegexpPattern;
	}

	
}
