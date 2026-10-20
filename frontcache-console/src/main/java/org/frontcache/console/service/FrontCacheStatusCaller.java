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
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.io.NodeStatsActionResponse;

/**
 * Reads one edge's numbers for the "Edges status" block.
 *
 * Asks {@code get-node-stats} rather than {@code get-cache-state}: the old action returns a display
 * map and opens a fresh Lucene reader on the node to build it, which was acceptable once per page
 * load and is not acceptable now the realtime page polls. {@code FrontCacheClient} handles a node too
 * old to know the action by falling back to {@code get-cache-state}, so a mixed fleet still reports
 * its entry counts - see {@code NodeStatsActionFallbackTest}.
 */
public class FrontCacheStatusCaller implements Callable<FrontCacheStatus> {

	private FrontCacheClient fcClient;

	public FrontCacheStatusCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public FrontCacheStatus call() throws Exception {

    	FrontCacheStatus fcStatus = new FrontCacheStatus();
		fcStatus.setName(fcClient.getName());
		fcStatus.setUrl(fcClient.getFrontCacheURL());
		fcStatus.setCachedAmount(-1);

		boolean available = true;
		try
		{
			NodeStatsActionResponse stats = fcClient.getNodeStatsActionResponse();
			if (null != stats)
				fcStatus.readFrom(stats);   // every number the node did not report stays UNKNOWN
			else
				available = false;

		} catch (Throwable ex) {
			available = false;
			ex.printStackTrace();
		}

		fcStatus.setAvailable(available);

        return fcStatus;
    }
}
