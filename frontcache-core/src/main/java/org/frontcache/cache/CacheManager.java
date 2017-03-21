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
package org.frontcache.cache;


import org.frontcache.FCConfig;
import org.frontcache.cache.impl.L1L2CacheProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class CacheManager {

	private static Logger logger = LoggerFactory.getLogger(CacheManager.class);
	private CacheManager() {}
	
	private static CacheProcessor instance;

	public static CacheProcessor getInstance(){
		if (null == instance) {
			instance = getCacheProcessor();
		}
		return instance;
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
