package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.JavaScriptPage;
import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebResponse;

/**
 * 
 * Static read text, js, img, etc
 *
 */
public class StaticReadTests extends CommonTestsBase {



	@Before
	public void setUp() throws Exception {
		super.setUp();

	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	@Test
	public void text1() throws Exception {
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");

		TextPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/static-read/a.txt");
		String pageAsText = page.getContent();
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("a", pageAsText);
	}
	
	@Test
	public void text2() throws Exception {
		webClient.addRequestHeader(FCHeaders.ACCEPT, "*/*");

		TextPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/static-read/a.txt");
		String pageAsText = page.getContent();
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("a", pageAsText);
	}
	
	@Test
	public void js() throws Exception {
		webClient.addRequestHeader(FCHeaders.ACCEPT, "*/*");

		JavaScriptPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/static-read/jquery.js");
		String pageAsText = page.getContent();
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		Assert.assertTrue(pageAsText.startsWith("(function(){var _jQuery=window.jQuery"));
		Assert.assertTrue(pageAsText.endsWith("br):0);};});})();"));
		
	}
	
	@Test
	public void img() throws Exception {

		String urlStr = TestConfig.FRONTCACHE_TEST_BASE_URI + "common/static-read/image.jpg";
		CloseableHttpClient httpclient = HttpClients.createDefault();
		
		HttpResponse response = null;

		HttpHost httpHost = FCUtils.getHttpHost(new URL(urlStr));
		HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(urlStr));
		httpRequest.addHeader(FCHeaders.ACCEPT, "image/webp,image/*,*/*;q=0.8");

		response = httpclient.execute(httpHost, httpRequest);
		
		byte[] data = getBytes(response.getEntity().getContent());
		
		assertEquals(167579, data.length);

		((CloseableHttpResponse) response).close();
		return;
	}

	
	private byte[] getBytes(InputStream is) throws Exception {
		
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            int bytesRead = 0;
            int bufferSize = 4000;
             byte[] byteBuffer = new byte[bufferSize];              
             while ((bytesRead = is.read(byteBuffer)) != -1) {
                 baos.write(byteBuffer, 0, bytesRead);
             }
             
             return baos.toByteArray();
        } catch (Exception e) {
        	e.printStackTrace();
        } finally {
            if (null != is)
            {
                try {
                    is.close();
                } catch (IOException e) {
                	e.printStackTrace();
                }
            }
        }
		
		return null;
	}
	
}
