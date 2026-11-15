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
package org.frontcache.bots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.frontcache.core.FCHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code client="..."} attribute of an {@code <fc:include>}: which visitors the fragment is resolved for.
 *
 * <ul>
 * <li>a name selects by is-a - {@code client="guest"} is every guest and every type {@code like guest}, so a page
 *     written before declared types existed keeps its audience;</li>
 * <li>a comma list selects any of its names - {@code client="customer, captcha-passed"};</li>
 * <li>{@code !name} excludes - {@code client="!customer"} is everyone who is not a customer, bots included, and
 *     {@code client="guest, !customer"} guests who are not. Resolved when the request is-a some positive name (or
 *     there is none) and is-a no negated one.</li>
 * </ul>
 *
 * A name this node does not know is WARNed (once per value) and dropped, and a list left with no name means "no
 * attribute" - resolved for everyone - which is what any value but {@code bot} / {@code guest} meant before: a typo
 * fails the way it always failed. {@code all}, the documented spelling of the default, is everyone without a warning.
 */
public final class ClientSelector {

	private static final Logger logger = LoggerFactory.getLogger(ClientSelector.class);

	/** what jsp-tags.md documents as the default - "all client types" */
	private static final String ALL = "all";

	// values already warned about - parsed on every page that carries the include, so once each, bounded
	private static final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public static final ClientSelector BOT = new ClientSelector(new String[] { FCHeaders.REQUEST_CLIENT_TYPE_BOT }, new String[0]);
	public static final ClientSelector GUEST = new ClientSelector(new String[] { FCHeaders.REQUEST_CLIENT_TYPE_GUEST }, new String[0]);

	private final String[] included;
	private final String[] excluded;

	private ClientSelector(String[] included, String[] excluded) {
		this.included = included;
		this.excluded = excluded;
	}

	/**
	 * @param value the attribute's value, or null
	 * @param types the node's vocabulary
	 * @return the selector, or null for "resolve for everyone" - no attribute, or no name this node knows
	 */
	public static ClientSelector parse(String value, VisitorTypes types) {
		if (null == value)
			return null;

		String trimmed = value.trim().toLowerCase(Locale.ROOT);
		// the common cases, without a split or an allocation
		if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(trimmed))
			return BOT;
		if (FCHeaders.REQUEST_CLIENT_TYPE_GUEST.equals(trimmed))
			return GUEST;
		if (ALL.equals(trimmed) || trimmed.isEmpty())
			return null;

		List<String> included = new ArrayList<String>(2);
		List<String> excluded = new ArrayList<String>(2);
		for (String raw : trimmed.split(","))
		{
			String name = raw.trim();
			boolean negated = name.startsWith("!");
			if (negated)
				name = name.substring(1).trim();
			if (name.isEmpty())
				continue;

			if (!types.isKnown(name))
			{
				if (warned.size() < 256 && warned.add(value))
					logger.warn("Unknown visitor type '" + name + "' in <fc:include client=\"" + value + "\"> - ignored;"
							+ " known: " + types.describeKnown() + " (logged once per value)");
				continue;
			}
			(negated ? excluded : included).add(name);
		}

		if (included.isEmpty() && excluded.isEmpty())
			return null;

		return new ClientSelector(included.toArray(new String[0]), excluded.toArray(new String[0]));
	}

	/** @return true when the fragment is resolved for a request of this visitor type (leaf) and client type (root) */
	public boolean selects(String visitorType, String clientType) {
		for (String name : excluded)
			if (VisitorTypes.isA(visitorType, clientType, name))
				return false;

		if (0 == included.length)
			return true;

		for (String name : included)
			if (VisitorTypes.isA(visitorType, clientType, name))
				return true;

		return false;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (String name : included)
			sb.append(0 < sb.length() ? ", " : "").append(name);
		for (String name : excluded)
			sb.append(0 < sb.length() ? ", " : "").append('!').append(name);
		return sb.toString();
	}
}
