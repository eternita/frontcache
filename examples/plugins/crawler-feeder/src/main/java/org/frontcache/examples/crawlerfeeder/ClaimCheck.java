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
package org.frontcache.examples.crawlerfeeder;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether an address claiming to be a crawler is one: its reverse DNS name is under one of the crawler's domains, and
 * that name resolves back to the address - the check Google, Bing and Apple document for their crawlers. A pure
 * function over a {@link Dns}; slow (two lookups), so never on a request thread.
 *
 * <p>Three answers, because what is done with "not confirmed" depends on why: {@link Outcome#REFUTED} is a
 * definitive no (a name outside the domains, or a forward answer without the address), {@link Outcome#UNKNOWN} is
 * no answer (no name, a lookup that failed) - an address already confirmed is not dropped for an unknown, only for a
 * refutation or when its confirmation runs out.
 */
final class ClaimCheck {

	enum Outcome {
		CONFIRMED, REFUTED, UNKNOWN
	}

	/**
	 * @param host   the reverse DNS name (lower case, no trailing dot), or null when there was none
	 * @param reason why it is not confirmed; null when it is
	 */
	record Verdict(Outcome outcome, String host, String reason) {

		boolean confirmed() {
			return Outcome.CONFIRMED == outcome;
		}
	}

	/**
	 * What a name written into a list's comment may hold - a host name and nothing else, so a PTR record can never put
	 * a line of its own into the list.
	 */
	static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

	private ClaimCheck() {
	}

	static Verdict check(Dns dns, InetAddress address, List<String> domains) {
		String name;
		try {
			name = dns.reverse(address);
		} catch (Exception e) {
			return new Verdict(Outcome.UNKNOWN, null, "the reverse lookup failed: " + e.getClass().getSimpleName());
		}
		if (null == name || name.isBlank())
			return new Verdict(Outcome.UNKNOWN, null, "the address has no name that resolves back to it");
		String host = name.strip().toLowerCase(Locale.ROOT);
		if (host.endsWith("."))
			host = host.substring(0, host.length() - 1);
		if (host.length() > 253 || !HOST.matcher(host).matches())
			return new Verdict(Outcome.REFUTED, null, "the address's name is not a host name");
		if (!underOneOf(host, domains))
			return new Verdict(Outcome.REFUTED, host, "the name is not under " + String.join(", ", domains));

		List<InetAddress> forward;
		try {
			forward = dns.forward(host);
		} catch (Exception e) {
			return new Verdict(Outcome.UNKNOWN, host, "the forward lookup failed: " + e.getClass().getSimpleName());
		}
		if (null == forward || forward.isEmpty())
			return new Verdict(Outcome.UNKNOWN, host, "the name resolves to no address");
		if (forward.contains(address))
			return new Verdict(Outcome.CONFIRMED, host, null);
		return new Verdict(Outcome.REFUTED, host, "the name does not resolve back to the address");
	}

	/** {@code crawl-1.googlebot.com} is under {@code googlebot.com}; {@code googlebot.com} itself and {@code evilgooglebot.com} are not. */
	static boolean underOneOf(String host, List<String> domains) {
		for (String domain : domains)
			if (host.endsWith("." + domain))
				return true;
		return false;
	}
}
