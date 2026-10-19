/*
 * Copyright 2015 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.tests.standalone;

import java.io.File;

import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.FallbackAndTimeoutTests;
import org.junit.AfterClass;

public class StandaloneFallbackAndTimeoutTests extends FallbackAndTimeoutTests {


/*
	// do not cleanup before customeFallbackTest2LoadFromURL()
	// failed scenario
	// 1. server started and fallfack files generated
	// 2. file deleted from here (setUpClass())
	// 3. tests failed because file deleted

	// clean up files after tests

	@BeforeClass
	public static void setUpClass() throws Exception {
		return;
	}
//*/

	/**
	 * Deletes the fallback file the run just fetched, so the NEXT run fetches it again.
	 *
	 * Not just tidiness: FileBasedFallbackResolver skips an existing fallback file, so leaving this
	 * one behind would make customFallbackTest2LoadFromURL pass by reading a stale file instead of
	 * exercising the init URL. The file is untracked for the same reason (see .gitignore); tests.sh
	 * removes it up front as well, for the run that never reaches this method.
	 */
	@AfterClass
	public static void tearDownClass() throws Exception {
		String frontcacheHome = System.getProperty(TestConfig.FRONTCACHE_TEST_PROJECT_DIR_KEY) + "/FRONTCACHE_HOME_STANDALONE";
		File fallbackDataFile = new File(new File(frontcacheHome), "fallbacks/fallback2.txt");
		if (fallbackDataFile.exists())
			fallbackDataFile.delete();
	}



	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getStandaloneBaseURLLocalhost();
	}

}
