package org.frontcache.cache.impl.lucene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.Properties;

import org.frontcache.cache.impl.lucene.LuceneCacheProcessor;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

public class WebResponseTests {

	protected Logger logger = LoggerFactory.getLogger(WebResponseTests.class);
	LuceneCacheProcessor pr = null;

	@Test
	public void fileSaveTest() throws Exception {

		String url = "someUrl";
		pr.removeFromCache(url);

		WebResponse response = new WebResponse(url, "<b>some text123</b>".getBytes());

		pr.putToCache(url, response);

		WebResponse fromFile = pr.getFromCache(url);
		// assertEquals(url, fromFile.getUrl());
		assertEquals("<b>some text123</b>", new String(fromFile.getContent()));

	}

	@Test
	public void fileDownloadTest() throws Exception {

		OkHttpClient client = new OkHttpClient();

		String url = "https://www.coinshome.net/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm";

		Request request = new Request.Builder().url(url).build();
		Response okResponse = client.newCall(request).execute();
		ResponseBody body = okResponse.body();
		byte[] data = body.bytes();
		assertNotNull(data);

		pr.removeFromCache(url);

		WebResponse response = new WebResponse(url, data);

		pr.putToCache(url, response);

		WebResponse fromFile = pr.getFromCache(url);
		assertEquals(url, fromFile.getUrl());
		assertEquals(new String(data), new String(fromFile.getContent()));

	}

	@Test
	public void fileRemoveTest() throws Exception {

		String url = "someUrl";

		WebResponse response = new WebResponse(url, "<b>some text123</b>".getBytes());

		pr.putToCache(url, response);

		WebResponse fromCache = pr.getFromCacheImpl(url);

		assertNotNull(fromCache);
		assertEquals(url, fromCache.getUrl());
		assertEquals(new String(response.getContent()), new String(fromCache.getContent()));
		assertEquals(response.getHeaders(), fromCache.getHeaders());
		assertEquals(response.getContentType(), fromCache.getContentType());
		assertEquals(response.getContentLenth(), fromCache.getContentLenth());
		assertEquals(response.getStatusCode(), fromCache.getStatusCode());

		pr.removeFromCache(url);

		fromCache = pr.getFromCacheImpl(url);

		assertNull(fromCache);
	}

	@Test(expected = NullPointerException.class)
	public void nullConfigTest() throws Exception {
		LuceneCacheProcessor pr = new LuceneCacheProcessor();

		pr.init(null);
		fail("Expected exception not thrown");
	}

	@Test
	public void testNotValidResponse() throws Exception {

		WebResponse response = new WebResponse("https://google.com/some/url", null);

		pr.getIndexManager().indexDoc(response);
		response = pr.getFromCache("https://google.com/some/url");
		assertNotNull(response);

		response = new WebResponse("https://google.com/some/url", "data".getBytes());

		pr.getIndexManager().indexDoc(response);
		response = pr.getFromCache("https://google.com/some/url");
		assertNotNull(response);
		assertEquals("https://google.com/some/url", response.getUrl());
		assertEquals("data", new String(response.getContent()));
	}

	@Before
	public void setUp() {
		pr = new LuceneCacheProcessor();
		Properties prop = new Properties();
		prop.put("front-cache.cache-processor.impl.cache-dir", "/private/tmp/cache/");
		pr.init(prop);
	}

	@After
	public void cleanUp() {
		pr.removeFromCacheAll();
		pr.destroy();

	}
}
