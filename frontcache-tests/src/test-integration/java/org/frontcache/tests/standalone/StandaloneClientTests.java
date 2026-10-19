/*
 * Copyright 2018 Eternita
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

import org.frontcache.tests.base.ClientTests;

/**
 *
 * run tests defined in CommonTests through it
 *
 *
 */
public class StandaloneClientTests extends ClientTests {

	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getStandaloneBaseURLLocalhost();
	}

	@Override
	public String getFrontCacheBaseURLLoopback() {
		return getStandaloneBaseURLLoopback();
	}

	/** the standalone node carries the rate-limit rule the guard e2e tests exercise */
	@Override
	protected boolean nodeShipsGuardRules() {
		return true;
	}

	/** this node's bots.conf is on the 2.9.0 rule format */
	@Override
	protected String expectedBotsConfigMarker() {
		return "headless-e2e";
	}

}
