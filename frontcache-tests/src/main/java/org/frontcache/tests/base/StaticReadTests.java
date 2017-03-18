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
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

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
public abstract class StaticReadTests extends TestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();

	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURLDomainFC1(); 
	
	@Test
	public void text1() throws Exception {
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");

		TextPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/static-read/a.txt");
		String pageAsText = page.getContent();
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("a", pageAsText);
	}
	
	@Test
	public void text2() throws Exception {
		webClient.addRequestHeader(FCHeaders.ACCEPT, "*/*");

		TextPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/static-read/a.txt");
		String pageAsText = page.getContent();
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("a", pageAsText);
	}
	
	@Test
	public void js() throws Exception {
		webClient.addRequestHeader(FCHeaders.ACCEPT, "*/*");

		JavaScriptPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/static-read/jquery.js");
		String pageAsText = page.getContent();
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		Assert.assertTrue(pageAsText.startsWith("(function(){var _jQuery=window.jQuery"));
		Assert.assertTrue(pageAsText.endsWith("br):0);};});})();"));
		
	}
	
	@Test
	public void img() throws Exception {

		String urlStr = getFrontCacheBaseURLDomainFC1() + "common/static-read/image.jpg";
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
	
}
