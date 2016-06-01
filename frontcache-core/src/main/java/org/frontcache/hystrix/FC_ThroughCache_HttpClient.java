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
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

public class FC_ThroughCache_HttpClient extends HystrixCommand<WebResponse> {

	private final String urlStr;
	private final Map<String, List<String>> requestHeaders;
	private final HttpClient client;
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_HttpClient.class);

    public FC_ThroughCache_HttpClient(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("FC_ThroughCache_HttpClient"))
        		);
        
        this.urlStr = urlStr;
        this.requestHeaders = requestHeaders;
        this.client = client;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpResponse response = null;

		try {
			HttpHost httpHost = FCUtils.getHttpHost(new URL(urlStr));
			HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(urlStr));

			// translate headers
			Header[] httpHeaders = FCUtils.convertHeaders(requestHeaders);
			for (Header header : httpHeaders)
				httpRequest.addHeader(header);
			
			response = client.execute(httpHost, httpRequest);
			WebResponse webResp = FCUtils.httpResponse2WebComponent(urlStr, response, context);
			return webResp;

		} catch (IOException ioe) {
			throw new FrontCacheException("Can't read from " + urlStr, ioe);
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
		context.setHystrixError();
		logger.error("FC - ORIGIN ERROR - " + this.urlStr);
        return fallbackForWebComponent(this.urlStr);
    }
    
	
	private WebResponse fallbackForWebComponent(String urlStr)
	{
		byte[] outContentBody = ("Fallabck for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
    
}