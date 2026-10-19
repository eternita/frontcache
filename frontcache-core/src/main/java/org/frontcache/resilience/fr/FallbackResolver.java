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
package org.frontcache.resilience.fr;

import java.util.Set;

import org.frontcache.core.FcHttpClient;
import org.frontcache.core.WebResponse;

/**
 * Resolves the canned response served when an origin call fails or its circuit is open.
 *
 * This is an extension point: {@code front-cache.fallback-resolver.impl} names the implementation and
 * it is loaded by reflection, so an implementation can live outside this jar.
 *
 * <b>Changed in 2.7:</b> {@code getFallbackConfigs()}, which returned
 * {@code Map<domain, Set<FallbackConfigEntry>>}, is now {@link #getFallbackEntries()} returning the set
 * directly. Multi-domain support was retired in 2.6, so that map had held exactly one entry ever since.
 * The old method was REMOVED rather than kept as a deprecated default: a default would have let an
 * implementation that still overrides only the old method compile and load, and then be silently
 * ignored - and "silently ignored" for a fallback resolver means the wrong content at the exact moment
 * the origin is already down. A compile error is the kinder failure. {@code FallbackResolverFactory}
 * additionally refuses to load an implementation that lacks the new method, so a jar compiled against
 * 2.6 and dropped in without recompiling fails at startup rather than with an AbstractMethodError on
 * the first fallback.
 */
public interface FallbackResolver {

	public void init(FcHttpClient client);

	public WebResponse getFallback(String fallbackSource, String urlStr);

	/**
	 * @return the configured fallback entries, for display in the console. Order matters: it is the
	 *         order they are matched in, first match wins.
	 */
	public Set<FallbackConfigEntry> getFallbackEntries();
}
