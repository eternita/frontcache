/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
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
