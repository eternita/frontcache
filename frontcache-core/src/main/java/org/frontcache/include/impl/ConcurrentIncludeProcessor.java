package org.frontcache.include.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorBase;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class ConcurrentIncludeProcessor extends IncludeProcessorBase implements IncludeProcessor {

	private int threadAmount = 1; // default 1 thread
	private long timeout = 10*1000; // default 10 second
	
    ExecutorService executor = null;

	public ConcurrentIncludeProcessor() {
	}

	@Override
	public void init(Properties properties) {
		try
		{
			String threadAmountStr = properties.getProperty("front-cache.include-processor.impl.concurrent.thread-amount");
			threadAmount = Integer.parseInt(threadAmountStr); 
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		logger.info("amount of threads: " + threadAmount);
		
		try
		{
			String timeoutStr = properties.getProperty("front-cache.include-processor.impl.concurrent.timeout");
			timeout = Integer.parseInt(timeoutStr); 
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		logger.info("timeout: " + timeout);
		
	    executor = Executors.newFixedThreadPool(threadAmount); 
	}


	@Override
	public void destroy() {
		executor.shutdown();
	}	
		
	/**
	 * 
	 * @param content
	 * @param hostURL
	 * @return
	 */
	public WebResponse processIncludes(WebResponse parentWebResponse, String hostURL, MultiValuedMap<String, String> requestHeaders, HttpClient client, RequestContext context)
	{
		String contentStr = new String(parentWebResponse.getContent());
		List<IncludeResolutionPlaceholder> includes = parseIncludes(contentStr, hostURL, requestHeaders, client, context);
		
		if (null == includes)
			return parentWebResponse;

        List<Future<IncludeResolutionPlaceholder>> futureList = new ArrayList<Future<IncludeResolutionPlaceholder>>(includes.size());
		for (IncludeResolutionPlaceholder inc : includes)
		{
			// run concurrent include resolution
            futureList.add(executor.submit(inc));
		}
      
        boolean timeoutReached = false;
        for (Future<IncludeResolutionPlaceholder> f : futureList)
        {
            try {
            	if (timeoutReached)
            		f.get(1, TimeUnit.MILLISECONDS);
            	else
            		f.get(timeout, TimeUnit.MILLISECONDS);
            		
            } catch (TimeoutException | InterruptedException | ExecutionException e) { 
                f.cancel(true);
                timeoutReached =  true;
            }
        }
		
        // replace placeholders with content
		WebResponse agregatedWebResponse = replaceIncludePlaceholders(contentStr, includes);
		return agregatedWebResponse;
	}	

	/**
	 * 
	 * @param content
	 * @param hostURL
	 * @return
	 */
	private List<IncludeResolutionPlaceholder> parseIncludes(String content, String hostURL, MultiValuedMap<String, String> requestHeaders, HttpClient client, RequestContext context)
	{
		List<IncludeResolutionPlaceholder> includes = null;
		
		int scanIdx = 0;
		while(scanIdx < content.length())
		{
			int startIdx = content.indexOf(START_MARKER, scanIdx);
			if (-1 < startIdx)
			{
				int endIdx = content.indexOf(END_MARKER, startIdx);
				if (-1 < endIdx)
				{
					endIdx = endIdx + END_MARKER.length();
					String includeTagStr = content.substring(startIdx, endIdx);
					String includeURL = getIncludeURL(includeTagStr);
					
					if (null == includes)
						includes = new ArrayList<IncludeResolutionPlaceholder>();
					
					// save placeholder
					includes.add(new IncludeResolutionPlaceholder(startIdx, endIdx, hostURL + includeURL, requestHeaders, client, context));
					
					scanIdx = endIdx;
				} else {
					// can't find closing 
					scanIdx = content.length(); // scan complete
				}
			} else {
				scanIdx = content.length(); // scan complete
			}
		}
		
		return includes;
	}
	
	/**
	 * 
	 * @param content
	 * @param includes
	 * @return
	 */
	private WebResponse replaceIncludePlaceholders(String content, List<IncludeResolutionPlaceholder> includes)
	{
		int scanIdx = 0;
		
		// paste content from includes to output doc
		WebResponse webResponse = new WebResponse("aggregation in " + this.getClass().getName());
		
		StringBuffer outSb = new StringBuffer();
		for (int i = 0; i < includes.size(); i++)
		{
			IncludeResolutionPlaceholder inc = includes.get(i);
			
			outSb.append(content.substring(scanIdx, inc.startIdx));
			if (FrontCacheEngine.debugComments)
				outSb.append("<!-- start fc:include ").append(inc.includeURL).append(" -->");
			
			outSb.append(new String(inc.webResponse.getContent()));
			
			if (FrontCacheEngine.debugComments)
				outSb.append("<!-- end fc:include ").append(inc.includeURL).append(" -->");
			
			mergeIncludeResponseHeaders(webResponse.getHeaders(), inc.webResponse.getHeaders());

			scanIdx = inc.endIdx;
		}
		outSb.append(content.substring(scanIdx)); // data after the last include
		webResponse.setContent(outSb.toString().getBytes());
		return webResponse;
	}
	
	/**
	 * 
	 * 
	 *
	 */
	class IncludeResolutionPlaceholder implements Callable<IncludeResolutionPlaceholder> {
		int startIdx;
		int endIdx;
		String includeURL;
		WebResponse webResponse;
		MultiValuedMap<String, String> requestHeaders; 
		HttpClient client;
		RequestContext context;
		
		public IncludeResolutionPlaceholder(int startIdx, int endIdx, String includeURL, MultiValuedMap<String, String> requestHeaders, HttpClient client, RequestContext context) {
			super();
			this.startIdx = startIdx;
			this.endIdx = endIdx;
			this.includeURL = includeURL;
			this.requestHeaders = requestHeaders;
			this.client = client;
			this.context = context;
		}

	    @Override
	    public IncludeResolutionPlaceholder call() throws Exception {
	    	
			try {
				this.webResponse = callInclude(this.includeURL, this.requestHeaders, this.client, this.context);

			} catch (FrontCacheException e) {
				logger.error("unexpected error processing include " + includeURL);
				
				StringBuffer outSb = new StringBuffer();
				outSb.append(includeURL);
				outSb.append("<!-- error processing include " + includeURL);
				outSb.append(e.getMessage());
				outSb.append(" -->");
				this.webResponse = new WebResponse(this.includeURL, outSb.toString().getBytes());

			} catch (Exception e) {
				e.printStackTrace();
				logger.error("unexpected error processing include " + includeURL);
				
				StringBuffer outSb = new StringBuffer();
				outSb.append(includeURL);
				outSb.append("<!-- unexpected error processing include " + includeURL);
				outSb.append(e.getMessage());
				outSb.append(" -->");
				this.webResponse = new WebResponse(this.includeURL, outSb.toString().getBytes());
			}
	    	
	        return this;
	    }
		
	}	
}


