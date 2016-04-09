package org.frontcache.coins;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class FrontcacheCoinsTest {

	public static final String FRONTCACHE_TEST_BASE_URI = "http://sg.coinshome.net:80/";
	
	protected Logger logger = LoggerFactory.getLogger(FrontcacheCoinsTest.class);  
	
	WebClient webClient = null;
	
	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}
	
/*
	@Test
	public void testImageOR() throws Exception {
		

		CloseableHttpClient httpclient = HttpClients.createDefault();
		
		HttpResponse response = null;
		String urlStr = "http://sg.coinshome.net/en/welcome.htm";

		try {
			HttpHost httpHost = FCUtils.getHttpHost(new URL(urlStr));
			HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(urlStr));//(verb, uri + context.getRequestQueryString());

//			// translate headers
//			Header[] httpHeaders = convertHeaders(requestHeaders);
//			for (Header header : httpHeaders)
//				httpRequest.addHeader(header);
			
			response = httpclient.execute(httpHost, httpRequest);
			
			Header respHeader = response.getFirstHeader(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
			Assert.assertNotNull(respHeader);
			assertEquals("true", respHeader.getValue());
			
			respHeader = response.getFirstHeader(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
			Assert.assertNotNull(respHeader);
			assertEquals("true", respHeader.getValue());

			BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
			StringBuffer result = new StringBuffer();
			String line = null;
			boolean firstLine = true;
			while ((line = rd.readLine()) != null) {
				if (firstLine) 
					firstLine = false;
				else
					result.append("\n"); // append '\n' because it's lost during rd.readLine() (in between lines)
				
				result.append(line);
			}
			
			String dataStr = result.toString();
			System.out.println(dataStr);
			
//			WebResponse webResp = httpResponse2WebComponent(urlStr, response);
//			return webResp;

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
//*/
	
	/**
	 * test if front cache is on for welcome page in Singapure
	 * @throws Exception
	 */
	@Test
	public void testWelcomePageCacheSG() throws Exception {
		
		HtmlPage page = webClient.getPage("http://sg.coinshome.net/en/welcome.htm");
		page = webClient.getPage("http://sg.coinshome.net/en/welcome.htm"); // request 2 time to make sure request is cached
		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		printHeaders(webResponse);
	}
	
	/**
	 * test if front cache is on for welcome page in Oregon
	 * @throws Exception
	 */
	@Test
	public void testWelcomePageCacheOR() throws Exception {
		
		HtmlPage page = webClient.getPage("http://or.coinshome.net/en/welcome.htm");
		page = webClient.getPage("http://or.coinshome.net/en/welcome.htm"); // request 2 time to make sure request is cached
		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		printHeaders(webResponse);
	}
	
	protected void printHeaders(WebResponse webResponse)
	{
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		String debugResponseTime = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME);
		String debugResponseSize = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE);
		
		String logStr = "cacheable: " + debugCacheable + ", cached:" + debugCached + ", responseTime: " + debugResponseTime + ", responseSize: " + debugResponseSize;
		logger.info(logStr);
		return;
	}
	
}

