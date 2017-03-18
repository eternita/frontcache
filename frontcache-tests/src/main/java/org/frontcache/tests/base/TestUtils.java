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

public class TestUtils {

	public TestUtils() {
		// TODO Auto-generated constructor stub
	}

	// X-frontcache.debug.request.0: success toplevel from-cache 15 65793 "http://localhost:9080/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm" frontcache-localhost-1 browser
	public static boolean isRequestFromCache(String headerLogStr)
	{
		boolean cached = false;
		
		if (-1 < headerLogStr.indexOf("from-cache"))
			cached = true;
		
		return cached;
	}
	
}
