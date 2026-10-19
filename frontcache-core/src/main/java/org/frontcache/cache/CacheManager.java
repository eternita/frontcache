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
package org.frontcache.cache;


import org.frontcache.FCConfig;
import org.frontcache.cache.impl.L1L2CacheProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class CacheManager {

	private static Logger logger = LoggerFactory.getLogger(CacheManager.class);
	private CacheManager() {}

	// volatile + double-checked locking: a plain "if (null == instance)" here was a check-then-act
	// race. Under production traffic two Jetty threads hitting the very first requests both saw
	// null and each built its own CacheProcessor, so L1L2CacheProcessor created TWO
	// LuceneIndexManagers on the same L2 index dir. One won write.lock; the other was left with a
	// permanently null IndexWriter, and because a live writer in this JVM owned the lock the
	// stale-lock self-heal (by design) refused to clear it - so whichever engine happened to hold
	// the loser logged "LockObtainFailedException: Lock held elsewhere" on every single request and
	// L2 stayed dead (reads included) until the next restart, which re-rolled the same dice.
	private static volatile CacheProcessor instance;

	/**
	 * @return the cache processor if one has been created, or null - never creating one.
	 *
	 * {@link #getInstance()} initialises on first call, which includes opening the Lucene index and
	 * taking its write lock. That is the right behaviour for the request path and the wrong behaviour
	 * for a metrics scrape: a node whose cache has not started yet must not have it started BY being
	 * scraped. Reporting no cache series for a few seconds is the correct answer.
	 */
	public static CacheProcessor existingInstance(){
		return instance;
	}

	public static CacheProcessor getInstance(){
		CacheProcessor localInstance = instance;
		if (null == localInstance) {
			synchronized (CacheManager.class) {
				localInstance = instance;
				if (null == localInstance) {
					localInstance = getCacheProcessor();
					instance = localInstance;
				}
			}
		}
		return localInstance;
	}


	private static CacheProcessor getCacheProcessor()
	{
		String cacheImplStr = FCConfig.getProperty("front-cache.cache-processor.impl");
		CacheProcessor cacheProcessor = null;
		if (null != cacheImplStr)
		{
			try
			{
				@SuppressWarnings("rawtypes")
				Class clazz = Class.forName(cacheImplStr);
				Object obj = clazz.newInstance();
				if (null != obj && obj instanceof CacheProcessor)
				{
					logger.info("Cache implementation loaded: " + cacheImplStr);
					cacheProcessor = (CacheProcessor) obj;

					// init with properties from config file
					cacheProcessor.init(FCConfig.getProperties());
					logger.info("Cache implementation initialized: " + cacheImplStr);

					return cacheProcessor;
				}
			} catch (Exception ex) {
				logger.error("Cant instantiate " + cacheImplStr + " Fallback - " + L1L2CacheProcessor.class.getName(), ex);
			}
		}

		logger.info("Default cache implementation is loaded - " + L1L2CacheProcessor.class.getName());
		cacheProcessor = new L1L2CacheProcessor();
		try
		{
			// init with properties from config file
			cacheProcessor.init(FCConfig.getProperties());
		} catch (Exception ex) {
			logger.error("Cant initalize default cache processor. ", ex);
		}

		return cacheProcessor;
	}

}
