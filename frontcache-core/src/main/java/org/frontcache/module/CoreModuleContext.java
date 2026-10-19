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
package org.frontcache.module;

import java.io.File;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.warmer.WarmRequest;

/** The engine's {@link ModuleContext}: each answer is read when asked, so a module never holds a stale one. */
public final class CoreModuleContext implements ModuleContext {

	private final String publicDomain;

	/** @param publicDomain {@code FrontCacheEngine.getPublicDomain()} - null when unset */
	public CoreModuleContext(String publicDomain) {
		this.publicDomain = publicDomain;
	}

	@Override
	public String publicDomain() {
		return publicDomain;
	}

	@Override
	public File frontcacheHome() {
		String home = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		return (null == home || home.isBlank()) ? null : new File(home);
	}

	@Override
	public String property(String key) {
		return FCConfig.getProperty(key);
	}

	@Override
	public CacheProcessor existingCacheProcessor() {
		return CacheManager.existingInstance();
	}

	@Override
	public String warmRequestToken() {
		return WarmRequest.token();
	}
}
