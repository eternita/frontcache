package org.frontcache.hystrix;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixThreadPoolKey;

public class FC_ThroughCache_HttpClient extends HystrixCommand<WebResponse> {

	private String currentRequestURL = "open-circuit-default-key"; // when circuit is open
	private String originRequestURL = null;
	private final Map<String, List<String>> requestHeaders;
	private final HttpClient client;
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_HttpClient.class);
	
    public FC_ThroughCache_HttpClient(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(context.getDomainContext().getDomain()))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Origin-Hits"))
                .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("Origin-Hits-Pool")));
        
        this.originRequestURL = urlStr;
        this.currentRequestURL = context.getCurrentRequestURL();
        this.requestHeaders = requestHeaders;
        this.client = client;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpResponse response = null;

		try {
			logger.debug("calling " + originRequestURL);
			HttpHost httpHost = FCUtils.getHttpHost(new URL(originRequestURL));
			HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(originRequestURL));

			// translate headers
			Header[] httpHeaders = FCUtils.convertHeaders(requestHeaders);
			for (Header header : httpHeaders)
				httpRequest.addHeader(header);
			
			response = client.execute(httpHost, httpRequest);
			WebResponse webResp = FCUtils.httpResponse2WebComponent(originRequestURL, response, context);
			return webResp;

		} catch (IOException ioe) {
			logger.error("Can't read from " + originRequestURL, ioe);
			throw new FrontCacheException("Can't read from " + originRequestURL, ioe);
		} finally {
			if (null != response)
				try {
					((CloseableHttpResponse) response).close();
				} catch (IOException e) {
					e.printStackTrace();
				} 
		}
		
    }
    
    @Override
    protected WebResponse getFallback() {
		context.setHystrixFallback();

		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();
			
		String includeCurrentURL = getIncludeCurrentURL(currentRequestURL, originRequestURL);
		logger.error("FC_ThroughCache_HttpClient - ERROR FOR - " + includeCurrentURL + " / " + originRequestURL + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context);
		
		WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(context.getDomainContext(), this.getClass().getName(), includeCurrentURL);
		
		return webResponse;
    }

    /**
     * 
     * @param currentRequestURL
     * @param originRequestURL
     * @return
     */
    private String getIncludeCurrentURL(String currentRequestURL, String originRequestURL)
    {
    	// host from current http://www.coinshome.net/common/hystrix/top1.jsp
    	// uri from original http://origin.coinshome.net:1234/common/hystrix/inc12.jsp
    	// ->
    	// includeCurrentURL http://www.coinshome.net/common/hystrix/inc12.jsp
    	
    	String includeCurrentURL = null;
    			
		int idx1 = currentRequestURL.indexOf("//");
		int idx2 = originRequestURL.indexOf("//");
		
		if (-1 < idx1 && -1 < idx2)
		{
			int idx11 = currentRequestURL.indexOf("/", idx1 + "//".length()); 
			int idx21 = originRequestURL.indexOf("/", idx2 + "//".length());
			
			if (-1 < idx11 && -1 < idx21)
			{
				includeCurrentURL = currentRequestURL.substring(0, idx11) + originRequestURL.substring(idx21);
			}
		}
		if (null == includeCurrentURL)
			includeCurrentURL = "can't get includeCurrentURL from " + currentRequestURL + " and " + originRequestURL;
			
		logger.debug("fallback includeCurrentURL " + includeCurrentURL + " based on currentRequestURL " + currentRequestURL + " and originRequestURL " + originRequestURL);
		
    	return includeCurrentURL;
    }
    
}