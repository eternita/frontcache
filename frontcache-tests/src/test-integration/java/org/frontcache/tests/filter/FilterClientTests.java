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
package org.frontcache.tests.filter;

import org.frontcache.tests.base.ClientTests;

public class FilterClientTests extends ClientTests {


	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getFilterBaseURLLocalhost();
	}

	@Override
	public String getFrontCacheBaseURLLoopback() {
		return getFilterBaseURLLoopback();
	}

	/** the filter node ships no guard-rules.conf, which is what keeps the "file absent" path covered */
	@Override
	protected boolean nodeShipsGuardRules() {
		return false;
	}

	/** this node's bots.conf is deliberately left bare-keyword-only */
	@Override
	protected String expectedBotsConfigMarker() {
		return "bare-keyword-only format";
	}

}
