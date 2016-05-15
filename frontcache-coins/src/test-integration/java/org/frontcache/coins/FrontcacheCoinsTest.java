package org.frontcache.coins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.io.GetFromCacheActionResponse;
import org.junit.After;
import org.junit.Before;
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
		webClient.getOptions().setThrowExceptionOnScriptError(false);
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
	
	@Test
	public void getFromCacheClientOR() throws Exception {

		HtmlPage page = webClient.getPage("https://or.coinshome.net/fc/include-footer.htm?locale=zh");
		
		FrontCacheClient frontcacheClient = new FrontCacheClient("http://or.coinshome.net/");
		
		String key = "https://origin.coinshome.net:443/fc/include-footer.htm?locale=zh";

		GetFromCacheActionResponse getFromCacheActionResponse = frontcacheClient.getFromCache(key);
		org.frontcache.core.WebResponse response = getFromCacheActionResponse.getValue();
		
		assertEquals(key, getFromCacheActionResponse.getKey());
		assertNotNull(getFromCacheActionResponse.getValue());
//		System.out.println("!!! - URL " + response.getUrl());
//		System.out.println("!!! - data " + new String(response.getContent()));
		
		return;
	}
	
	@Test
	public void getFromCacheClientOR2() throws Exception {
		
		HtmlPage page = webClient.getPage("http://or.coinshome.net/ru/coin_definition-500_%D0%A0%D0%B5%D0%B9%D1%81-%D0%97%D0%BE%D0%BB%D0%BE%D1%82%D0%BE-%D0%9F%D0%BE%D1%80%D1%82%D1%83%D0%B3%D0%B0%D0%BB%D0%B8%D1%8F_%D0%9A%D0%BE%D1%80%D0%BE%D0%BB%D0%B5%D0%B2%D1%81%D1%82%D0%B2%D0%BE_%D0%9F%D0%BE%D1%80%D1%82%D1%83%D0%B3%D0%B0%D0%BB%D0%B8%D1%8F_(1139_1910)-wMYK.GJA5KgAAAEtOOq374Fr.htm");
		FrontCacheClient frontcacheClient = new FrontCacheClient("http://or.coinshome.net/");
		
		String key = "http://origin.coinshome.net:80/ru/coin_definition-500_%D0%A0%D0%B5%D0%B9%D1%81-%D0%97%D0%BE%D0%BB%D0%BE%D1%82%D0%BE-%D0%9F%D0%BE%D1%80%D1%82%D1%83%D0%B3%D0%B0%D0%BB%D0%B8%D1%8F_%D0%9A%D0%BE%D1%80%D0%BE%D0%BB%D0%B5%D0%B2%D1%81%D1%82%D0%B2%D0%BE_%D0%9F%D0%BE%D1%80%D1%82%D1%83%D0%B3%D0%B0%D0%BB%D0%B8%D1%8F_(1139_1910)-wMYK.GJA5KgAAAEtOOq374Fr.htm";
		
		GetFromCacheActionResponse getFromCacheActionResponse = frontcacheClient.getFromCache(key);
		org.frontcache.core.WebResponse response = getFromCacheActionResponse.getValue();
		
		assertEquals(key, getFromCacheActionResponse.getKey());
		assertNotNull(getFromCacheActionResponse.getValue());
//		System.out.println("!!! - URL " + response.getUrl());
//		System.out.println("!!! - data " + new String(response.getContent()));
		
		return;
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

