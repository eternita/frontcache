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
package org.frontcache.console.service;

import java.util.concurrent.Callable;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.VisitorTypesConfig;
import org.frontcache.io.GetBotsActionResponse;

public class VisitorTypesCaller implements Callable<VisitorTypesConfig> {

	private FrontCacheClient fcClient;

	public VisitorTypesCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public VisitorTypesConfig call() throws Exception {

		// One call, both shapes: an edge older than 2.9.0 answers with keywords and no rules.
		GetBotsActionResponse response = fcClient.getBotsActionResponse();
		if (null == response)
			return new VisitorTypesConfig(fcClient.getName(), null, null);

		return new VisitorTypesConfig(fcClient.getName(), response.getBotKeywords(), response.getBotRules());
    }
}
