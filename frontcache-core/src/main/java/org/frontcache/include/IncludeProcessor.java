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
package org.frontcache.include;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public interface IncludeProcessor {

	final static int MAX_RECURSION_LEVEL = 10;
	
	final static int MAX_INCLUDE_LENGHT = 500; // max distance/length for include tag with URL inside	
	
	public void init(Properties properties);
	
	public void destroy();

	public boolean hasIncludes(WebResponse webResponse, int recursionLevel);

	public WebResponse processIncludes(WebResponse parentWebResponse, String appOriginBaseURL, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context, int recursionLevel);
	
}
