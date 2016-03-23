package org.frontcache.include.impl;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.http.client.HttpClient;
import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorBase;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class SerialIncludeProcessor extends IncludeProcessorBase implements IncludeProcessor {

	public SerialIncludeProcessor() {
	}

	/**
	 * 
	 * @param content
	 * @param hostURL
	 * @return
	 */
	public WebResponse processIncludes(WebResponse parentWebResponse, String hostURL, MultiValuedMap<String, String> requestHeaders, HttpClient client)
	{
		String content = parentWebResponse.getContent();
		StringBuffer outSb = new StringBuffer();
		
		MultiValuedMap<String, String> outHeaders = new ArrayListValuedHashMap<String, String>();
		int includeCounter = 0;
		
		int scanIdx = 0;
		while(scanIdx < content.length())
		{
			int startIdx = content.indexOf(START_MARKER, scanIdx);
			if (-1 < startIdx)
			{
				int endIdx = content.indexOf(END_MARKER, startIdx);
				if (-1 < endIdx)
				{
					String includeTagStr = content.substring(startIdx, endIdx + END_MARKER.length());
					String includeURL = getIncludeURL(includeTagStr);
					
					outSb.append(content.substring(scanIdx, startIdx));
					
					try {
						WebResponse webResponse = callInclude(hostURL + includeURL, requestHeaders, client);
						if (FrontCacheEngine.debugComments)
							outSb.append("<!-- start fc:include ").append(includeURL).append(" -->"); // for debugging
						
						outSb.append(webResponse.getContent());
						
						if (FrontCacheEngine.debugComments)
							outSb.append("<!-- end fc:include ").append(includeURL).append(" -->"); // for debugging
						
						mergeIncludeResponseHeaders(outHeaders, webResponse.getHeaders());
						includeCounter++;

					} catch (FrontCacheException e) {
						e.printStackTrace();
						logger.error("unexpected error processing include " + includeURL);
						
						outSb.append(includeURL);
						outSb.append("<!-- error processing include " + includeURL);
						outSb.append(e.getMessage());
						outSb.append(" -->");

					} catch (Exception e) {
						logger.error("unexpected error processing include " + includeURL);
						e.printStackTrace();
						
						outSb.append(includeURL);
						outSb.append("<!-- unexpected error processing include " + includeURL);
						outSb.append(e.getMessage());
						outSb.append(" -->");
					}
					
					
					scanIdx = endIdx + END_MARKER.length();
				} else {
					// can't find closing 
					outSb.append(content.substring(scanIdx, content.length()));
					scanIdx = content.length(); // scan complete
				}
				
				
			} else {
				outSb.append(content.substring(scanIdx, content.length()));
				scanIdx = content.length(); // scan complete
			}
		}
		
		if (0 == includeCounter)
		{   // if no includes - return parent web response
			return parentWebResponse;
		} else {
			WebResponse outResponse = new WebResponse("aggregation in " + this.getClass().getName()); // it has no URL because it's sum of processed include resp texts and include resp headers
			outResponse.setContent(outSb.toString());
			outResponse.setHeaders(outHeaders);
			
			return outResponse; 
		}

	}
	
}
