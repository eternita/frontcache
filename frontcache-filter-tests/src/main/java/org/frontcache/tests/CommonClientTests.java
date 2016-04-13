package org.frontcache.tests;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.frontcache.client.FrontCacheClient;

public class CommonClientTests {

	protected Logger logger = LoggerFactory.getLogger(CommonClientTests.class);  

	@Test
	public void getCacheStatus() throws Exception {
		
		FrontCacheClient fcc = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		String response = fcc.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("cache status"));
		logger.debug("response " + response);
	}

}
