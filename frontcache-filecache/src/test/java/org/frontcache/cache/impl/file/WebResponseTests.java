package org.frontcache.cache.impl.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
import static org.frontcache.cache.impl.file.IndexManager.*;

public class WebResponseTests {

	protected Logger logger = LoggerFactory.getLogger(WebResponseTests.class);  
	FilecacheProcessor pr = null;
	
	@Test
	public void fileSaveTest() throws Exception {

		
		String url = "someUrl";
		pr.removeFromCache(url);
		
		WebResponse response = new WebResponse(url, "<b>some text123</b>".getBytes());
		
		pr.putToCache(url, response);
		
		WebResponse fromFile = pr.getFromCache(url);
		//assertEquals(url, fromFile.getUrl());
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


		pr.removeFromCache(url);
		
		WebResponse response = new WebResponse(url, data);
		
		pr.putToCache(url, response);
		
		WebResponse fromFile = pr.getFromCache(url);
		//assertEquals(url, fromFile.getUrl());
		assertEquals(new String(data), new String(fromFile.getContent()));
		
	}
	
	@Test
	public void fileRemoveTest() throws Exception {

		String url = "someUrl";
		
		WebResponse response = new WebResponse(url, "<b>some text123</b>".getBytes());
		
		pr.putToCache(url, response);
		
		Path file = FilecacheProcessor.getCacheFile(url);
		
		assertTrue(Files.exists(file));
		
		pr.removeFromCache(url);
		
		file = FilecacheProcessor.getCacheFile(url);

		assertFalse(Files.exists(file));
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
		
		String firsthash = getHash(url);
		
		for (int i =0; i < 10; i++){
			String hash = getHash(url);
			assertEquals(firsthash, hash);
		}
	}
	
	 @Before public  void setUp() {
		pr = new FilecacheProcessor();
		Properties prop = new Properties();
		prop.put("front-cache.file-processor.impl.cache-dir", "/private/tmp/cache/");
		pr.init(prop);
   }

   
	 @After public  void cleanUp(){
	     pr.removeFromCacheAll();
		 pr.destroy();

   }  
}
