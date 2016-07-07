package org.frontcache.tests.standalone;

import java.io.File;

import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.FallbackHystrixTests;
import org.junit.BeforeClass;

public class StandaloneFallbackHystrixTests extends FallbackHystrixTests {

	
	@BeforeClass
	public static void setUpClass() throws Exception {

		// cleanup for for customeFallbackTest2LoadFromURL()
		String frontcacheHome = System.getProperty(TestConfig.FRONTCACHE_TEST_PROJECT_DIR_KEY) + "/FRONTCACHE_HOME_STANDALONE";
		File fallbackDataFile = new File(new File(frontcacheHome), "fallbacks/fallback2.txt");
		if (fallbackDataFile.exists())
			fallbackDataFile.delete();

		return;
	}

	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURL();
	}

}
