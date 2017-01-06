package org.frontcache.tests.base;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.tests.TestConfig;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;

/**
 * 
 * Defined tests are run in filter & standalone modes
 *
 */
public class TestsBase {

	protected WebClient webClient = null;
	
	protected FrontCacheClient frontcacheClientStandalone = null;
	protected FrontCacheClient frontcacheClientFilter = null;
	
	protected Logger logger = LoggerFactory.getLogger(TestsBase.class);  


	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		
		frontcacheClientStandalone = new FrontCacheClient(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_FC1, SiteKeys.TEST_SITE_KEY_1);
		frontcacheClientStandalone.removeFromCacheAll(); // clean up		
		
		frontcacheClientFilter = new FrontCacheClient(TestConfig.FRONTCACHE_FILTER_TEST_BASE_URI_FC1, SiteKeys.TEST_SITE_KEY_1);
		frontcacheClientFilter.removeFromCacheAll(); // clean up		
		
	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
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
	
	protected byte[] getBytes(InputStream is) throws Exception {
		
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
	
	
	protected static String getFilterBaseURLLocalhost()
	{
		return TestConfig.FRONTCACHE_FILTER_TEST_BASE_URI_LOCALHOST;
	}

	protected static String getStandaloneBaseURLLocalhost()
	{
		return TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST;
	}

	protected static String getFilterBaseURLDomainFC1()
	{
		return TestConfig.FRONTCACHE_FILTER_TEST_BASE_URI_FC1;
	}

	protected static String getStandaloneBaseURLDomainFC1()
	{
		return TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_FC1;
	}

	protected static String getFilterBaseURLDomainFC2()
	{
		return TestConfig.FRONTCACHE_FILTER_TEST_BASE_URI_FC2;
	}

	protected static String getStandaloneBaseURLDomainFC2()
	{
		return TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_FC2;
	}
	
}
