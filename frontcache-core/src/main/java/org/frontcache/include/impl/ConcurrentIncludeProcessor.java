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

import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorBase;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class ConcurrentIncludeProcessor extends IncludeProcessorBase implements IncludeProcessor {

	private int threadCount = 20;
	private long timeout = 3000;
	
    ExecutorService executor = null;

	public ConcurrentIncludeProcessor() {
	}

	@Override
	public void init(Properties properties) {
		threadCount = 20;
		timeout = 3000;
	    executor = Executors.newFixedThreadPool(threadCount); 
	}



	@Override
	public void destroy() {
		executor.shutdown();
	}	
		
	/**
	 * 
	 * @param content
	 * @param appOriginBaseURL
	 * @return
	 */
	public String processIncludes(String content, String appOriginBaseURL)
	{
		List<IncludeResolutionPlaceholder> includes = parseIncludes(content, appOriginBaseURL);
		
		if (null == includes)
			return content;

        List<Future<IncludeResolutionPlaceholder>> futureList = new ArrayList<Future<IncludeResolutionPlaceholder>>(includes.size());
		for (IncludeResolutionPlaceholder inc : includes)
		{
			//TODO: for cached - run serial
			
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
		String out = replaceIncludePlaceholders(content, includes);
		return out;
	}	

	/**
	 * 
	 * @param content
	 * @param appOriginBaseURL
	 * @return
	 */
	private List<IncludeResolutionPlaceholder> parseIncludes(String content, String appOriginBaseURL)
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
					includes.add(new IncludeResolutionPlaceholder(startIdx, endIdx, appOriginBaseURL + includeURL));
					
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
	private String replaceIncludePlaceholders(String content, List<IncludeResolutionPlaceholder> includes)
	{
		int scanIdx = 0;
		
		// paste content from includes to output doc
		StringBuffer outSb = new StringBuffer();
		for (int i = 0; i < includes.size(); i++)
		{
			IncludeResolutionPlaceholder inc = includes.get(i);
			
			outSb.append(content.substring(scanIdx, inc.startIdx));
			outSb.append(inc.content);

			scanIdx = inc.endIdx;
		}
		outSb.append(content.substring(scanIdx)); // data after the last include
		return outSb.toString();
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
		String content;
		public IncludeResolutionPlaceholder(int startIdx, int endIdx, String includeURL) {
			super();
			this.startIdx = startIdx;
			this.endIdx = endIdx;
			this.includeURL = includeURL;
		}

	    @Override
	    public IncludeResolutionPlaceholder call() throws Exception {
			this.content = callInclude(this.includeURL);
	        return this;
	    }
		
	}	
}


