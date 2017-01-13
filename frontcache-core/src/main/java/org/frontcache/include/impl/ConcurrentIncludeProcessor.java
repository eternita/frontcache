package org.frontcache.include.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.http.client.HttpClient;
import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorBase;

import com.netflix.hystrix.exception.HystrixRuntimeException;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class ConcurrentIncludeProcessor extends IncludeProcessorBase implements IncludeProcessor {

	private int threadAmount = 1; // default 1 thread
	private long timeout = 6*1000; // default 6 second
	
    ExecutorService executor = null;

	public ConcurrentIncludeProcessor() {
	}

	@Override
	public void init(Properties properties) {
		try
		{
			String threadAmountStr = properties.getProperty("front-cache.include-processor.impl.concurrent.thread-amount");
			if (null != threadAmountStr && threadAmountStr.trim().length() > 0)
				threadAmount = Integer.parseInt(threadAmountStr); 
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		logger.info("amount of threads: " + threadAmount);
		
		try
		{
			String timeoutStr = properties.getProperty("front-cache.include-processor.impl.concurrent.timeout");
			if (null != timeoutStr && timeoutStr.trim().length() > 0)
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
	public WebResponse processIncludes(WebResponse parentWebResponse, String hostURL, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context)
	{
		String contentStr = new String(parentWebResponse.getContent());
		List<IncludeResolutionPlaceholder> includes = parseIncludes(contentStr, hostURL, requestHeaders, client, context);
		
		if (null == includes)
			return parentWebResponse;

        List<Future<IncludeResolutionPlaceholder>> futureList = new ArrayList<Future<IncludeResolutionPlaceholder>>(includes.size()); // for sync includes

        for (IncludeResolutionPlaceholder inc : includes)
		{
        	
			boolean performInclude = false; // check for client specific include
			if (null == inc.includeClientType)
			{
				performInclude = true;
				
			} else if (inc.includeClientType.equalsIgnoreCase(context.getClientType())) {

				// client type specific include
				performInclude = true;
			} else {
				
				// client type for include and request doesnt match 
				performInclude = false;
			}
        	
        	inc.performInclude = performInclude; // save for include resolution
        	
			if (performInclude)
			{
				if (INCLUDE_TYPE_ASYNC.equals(inc.includeType))
				{
					// run concurrent include resolution
					// for Async includes - do NOT wait for response from Origin
					// response from Origin is NOT included to response to client
					// useful for counters - e.g. response totally from cache (fast) and async call to origin 
					executor.submit(inc);
				} else {
					// run concurrent include resolution
					// for Sync includes - wait for response from Origin (until timeout)
					// origin responses are sent to client
		            futureList.add(executor.submit(inc));
				}
			} // if (performInclude)
		}
      
		// processing timeouts for Sync includes 
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
                logger.debug("timeout (" + timeout + ") reached for resolving includes. Some includes may not be resolved ");
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
	private List<IncludeResolutionPlaceholder> parseIncludes(String content, String hostURL, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context)
	{
		List<IncludeResolutionPlaceholder> includes = null;
		
		long parsingStartTimeMillis = System.currentTimeMillis();
		
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
					String includeType = getIncludeType(includeTagStr);
					
					Map<String, List<String>> includeRequestHeaders = requestHeaders;
					if (INCLUDE_TYPE_ASYNC.equals(includeType))
					{
						// create a copy and extend it
						includeRequestHeaders = new HashMap<String, List<String>>();
						includeRequestHeaders.putAll(requestHeaders);
						includeRequestHeaders.put(FCHeaders.X_FRONTCACHE_ASYNC_INCLUDE, Arrays.asList(new String[]{"true"}));
					}
					
					String includeClientType = getIncludeClientType(includeTagStr);
					
					if (null == includes)
						includes = new ArrayList<IncludeResolutionPlaceholder>();
					
					// save placeholder
					includes.add(new IncludeResolutionPlaceholder(startIdx, endIdx, hostURL + includeURL, includeType, includeClientType, includeRequestHeaders, client, context));
					
					scanIdx = endIdx;
				} else {
					// can't find closing 
					scanIdx = content.length(); // scan complete
				}
			} else {
				scanIdx = content.length(); // scan complete
			}
		}
		
		logger.info("Includes parsing time: " + (System.currentTimeMillis() - parsingStartTimeMillis) + " ms");
		
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

	        if (null != inc.webResponse) {
	        	logger.debug("include "  + inc.includeURL + " has content");
	        	
	        } else if (!inc.performInclude) {
	        	// it's client specific include and should not be performed -> replace include tag with blank string
	        } else { 
	        	logger.debug("include detais "  + inc.includeURL + " content is not resolved due to timeout (" + timeout + ")  getting defaults");
	        	inc.webResponse = FallbackResolverFactory.getInstance().getFallback(inc.context.getDomainContext(), this.getClass().getName(), inc.includeURL);
	        }
	        	
			
			outSb.append(content.substring(scanIdx, inc.startIdx));

			if (INCLUDE_TYPE_SYNC.equals(inc.includeType))
			{
				if (FrontCacheEngine.debugComments)
					outSb.append("<!-- start fc:include ").append(inc.includeURL).append(" -->");
				
				if (null != inc.webResponse)
					outSb.append(new String(inc.webResponse.getContent()));
				
				if (FrontCacheEngine.debugComments)
					outSb.append("<!-- end fc:include ").append(inc.includeURL).append(" -->");
				
				if (null != inc.webResponse)
					mergeIncludeResponseHeaders(webResponse.getHeaders(), inc.webResponse.getHeaders());
			}

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
		String includeType; // sync|async
		String includeClientType; // bot|browser
		WebResponse webResponse;
		Map<String, List<String>> requestHeaders; 
		HttpClient client;
		RequestContext context;
		boolean performInclude = true;
		
		public IncludeResolutionPlaceholder(int startIdx, int endIdx, String includeURL, String includeType, String includeClientType, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) {
			super();
			this.startIdx = startIdx;
			this.endIdx = endIdx;
			this.includeURL = includeURL;
			this.includeType = includeType;
			this.includeClientType = includeClientType;
			this.requestHeaders = requestHeaders;
			this.client = client;
			this.context = context;
		}

	    @Override
	    public IncludeResolutionPlaceholder call() throws Exception {
	    	
			try {
				this.webResponse = callInclude(this.includeURL, this.requestHeaders, this.client, this.context);

			} catch (FrontCacheException e) {
				logger.error("FrontCacheException: unexpected error processing include " + includeURL, e);
			} catch (HystrixRuntimeException e) {
				logger.error("HystrixRuntimeException: unexpected error processing include " + includeURL, e);
				// in multi threaded environment hystrix does not resolve fallback ??
				// so, get fallback manually 
			} catch (Throwable e) {
				logger.error("Throwable: unexpected error processing include " + includeURL, e);
			} finally {
				if (null == this.webResponse)
				{
					// dont get fallback for async calls
					// when async call -> webResponse is null because we don't wait for response
					// get fallback for SYNC includes only 
					if (!INCLUDE_TYPE_ASYNC.equals(includeType))
						this.webResponse = FallbackResolverFactory.getInstance().getFallback(this.context.getDomainContext(), this.getClass().getName(), includeURL);
				}
			}
	    	
	        return this;
	    }
		
	}	
}


