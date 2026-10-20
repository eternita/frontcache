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
package org.frontcache.io;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Translates between a flat collection and the pre-2.7 {@code Map<domain, collection>} the management
 * API used to return for {@code get-bots}, {@code get-dynamic-urls} and {@code get-fallback-configs}.
 *
 * Why both shapes exist at once: <b>the console is a separate deployment from the nodes it reads.</b>
 * There is no moment at which they upgrade together, so each direction of skew has to work - a 2.6
 * console reading a 2.7 node, and a 2.7 console reading a 2.6 node. So a 2.7 node emits the flat field
 * AND the legacy map, and a 2.7 client accepts either. The cost is a duplicated collection in three
 * small config dumps; the alternative is an upgrade order that has to be got right by hand.
 *
 * Why the map had to go: one node serves one site, so it always held exactly one entry, and the
 * console's JSPs had to switch on the literal {@code "default-domain"} to find it - a domain concept
 * surviving in the wire format after the feature behind it was retired.
 *
 * The legacy field can be dropped once no supported console predates 2.7.
 */
final class LegacyDomainKeyedField {

	/**
	 * The key the pre-2.7 single-entry map used - {@code FCConfig.DEFAULT_DOMAIN}'s value, repeated
	 * here rather than referenced so that a wire-format constant cannot be changed by editing config
	 * code. The console's own JSPs hard-code the same literal.
	 */
	static final String LEGACY_DOMAIN_KEY = "default-domain";

	private LegacyDomainKeyedField() { }

	/** @return the legacy single-entry map, or null when there is nothing to wrap */
	static <T> Map<String, Set<T>> wrap(Set<T> flat)
	{
		if (null == flat)
			return null;

		return Collections.singletonMap(LEGACY_DOMAIN_KEY, flat);
	}

	/**
	 * @return every value in the map, unioned, or null when there is nothing to flatten
	 *
	 * The union matters only in theory - a post-retirement node sends one entry - but a genuinely old
	 * multi-domain node could send several, and dropping all but one of them silently would be worse
	 * than showing their union.
	 */
	static <T> Set<T> flatten(Map<String, Set<T>> byDomain)
	{
		if (null == byDomain)
			return null;

		Set<T> flat = new LinkedHashSet<T>();
		for (Set<T> values : byDomain.values())
			if (null != values)
				flat.addAll(values);

		return flat;
	}

}
