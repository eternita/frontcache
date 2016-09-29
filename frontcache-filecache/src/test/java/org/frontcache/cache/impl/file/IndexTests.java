package org.frontcache.cache.impl.file;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;



public class IndexTests {

	protected Logger logger = LoggerFactory.getLogger(IndexTests.class);
	FilecacheProcessor pr = null;

	
	@Test public void fileSaveTest() throws Exception {
        String[] tags = {"apple", "limon", "banana", "chery", "peach"};
		for (int i = 0; i < 5; i++) {
			String url = UUID.randomUUID().toString();
			String content = UUID.randomUUID().toString();
			pr.removeFromCache(url);

			WebResponse response = new WebResponse(url, content.getBytes());
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> set = new HashSet<>();
			set.add(tags[i]);
			response.setTags(set);
			response.setContentType(UUID.randomUUID().toString());
			response.setStatusCode(55);
			pr.putToCache(url, response);
			
			WebResponse fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentType(), fromFile.getContentType());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
		}
		
		pr.getIndexManager().searchByTag("apple", hash-> System.out.println(hash));

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
