package org.frontcache.cache.impl.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Properties;

import org.frontcache.core.WebResponse;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

public class WebResponseTests {

	protected Logger logger = LoggerFactory.getLogger(WebResponseTests.class);  

	@Test
	public void fileSaveTest() throws Exception {
		
		
		FilecacheProcessor pr = new FilecacheProcessor();
		pr.init(new Properties());
		
		String url = "someUrl";
		pr.removeFromCache(url);
		
		WebResponse response = new WebResponse(url, "<b>some text123</b>".getBytes());
		
		pr.putToCache(url, response);
		
		WebResponse fromFile = pr.getFromCache(url);
		assertEquals(url, fromFile.getUrl());
		assertEquals("<b>some text123</b>", new String (fromFile.getContent()));
		
	}
	
	@Test
	public void fileDownloadTest() throws Exception {
		
		OkHttpClient client = new OkHttpClient();

		String url = "https://www.coinshome.net/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm";
		
		Request request = new Request.Builder()
		                     .url(url)
		                     .build();
		Response okResponse = client.newCall(request).execute();
		ResponseBody body = okResponse.body();
		byte[] data = body.bytes();
		FilecacheProcessor pr = new FilecacheProcessor();
		pr.init(new Properties());
		pr.removeFromCache(url);
		
		WebResponse response = new WebResponse(url, data);
		
		pr.putToCache(url, response);
		
		WebResponse fromFile = pr.getFromCache(url);
		assertEquals(url, fromFile.getUrl());
		assertEquals(new String(data), new String(fromFile.getContent()));
		
	}
	
	@Test
	public void fileRemoveTest() throws Exception {

		FilecacheProcessor pr = new FilecacheProcessor();
		pr.init(new Properties());
		String url = "someUrl";
		
		WebResponse response = new WebResponse(url, "<b>some text123</b>".getBytes());
		
		pr.putToCache(url, response);
		
		File file = FilecacheProcessor.getCacheFile(url);
		
		assertTrue(file.exists());
		
		pr.removeFromCache(url);
		
		file = FilecacheProcessor.getCacheFile(url);

		assertFalse(file.exists());
	}
	
	@Test(expected=NullPointerException.class)
	public void nullConfigTest() throws Exception {
		FilecacheProcessor pr = new FilecacheProcessor();
		
		pr.init(null);
		fail("Expected exception not thrown");
	}
	
	@Test
	public void testHash() throws Exception {
		
		String url = "https://google.com/some/url";
		
		String firsthash = FilecacheProcessor.getHash(url);
		
		for (int i =0; i < 10; i++){
			String hash = FilecacheProcessor.getHash(url);
			assertEquals(firsthash, hash);
		}
	}
}
