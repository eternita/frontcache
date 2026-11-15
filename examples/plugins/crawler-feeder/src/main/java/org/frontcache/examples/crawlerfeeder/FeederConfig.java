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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.frontcache.plugin.PluginConfigException;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * {@code conf/crawler-feeder.conf}, read whole or refused whole. Absent (or without {@code feeds}) it is Google's,
 * Bing's and Apple's published crawler ranges, refreshed daily, each with the reverse-DNS check of claimants a rule
 * named {@code <feed>-claim} reports:
 *
 * <pre>
 * feeds = [
 *   { name = googlebot, url = "https://developers.google.com/static/search/apis/ipranges/googlebot.json", every = 24h,
 *     domains = [googlebot.com, google.com] }
 *   { name = bingbot,   url = "https://www.bing.com/toolbox/bingbot.json", domains = [search.msn.com] }
 *   { name = applebot,  url = "https://search.developer.apple.com/applebot.json", domains = [applebot.apple.com] }
 * ]
 * timeout = 30s        # connecting and the whole answer, each
 * keep = 7d            # a confirmed claimant stays listed this long after its last confirmation
 * retry = 1h           # an address not confirmed is not looked up again before this
 * max-verified = 1000  # confirmed addresses per list
 * </pre>
 *
 * Each feed keeps {@code conf/lists/<name>.txt}; a rule reads it as {@code client-ip:@lists/<name>.txt}. A feed with
 * {@code domains} and no {@code url} keeps a list of confirmed claimants only - a crawler that publishes no ranges.
 */
record FeederConfig(List<Feed> feeds, Duration timeout, Duration keep, Duration retry, int maxVerified) {

	/**
	 * One crawler: where its ranges are published (or null), which list it keeps, how often; and the guard rules whose
	 * matches report a claimant to check, with the domains its reverse DNS name must be under (none: no check).
	 */
	record Feed(String name, URI url, Duration every, List<String> rules, List<String> domains) {

		boolean fetches() {
			return null != url;
		}

		boolean verifies() {
			return !domains.isEmpty();
		}
	}

	static final String GOOGLEBOT = "https://developers.google.com/static/search/apis/ipranges/googlebot.json";
	static final String BINGBOT = "https://www.bing.com/toolbox/bingbot.json";
	static final String APPLEBOT = "https://search.developer.apple.com/applebot.json";
	static final Duration DEFAULT_EVERY = Duration.ofHours(24);
	static final Duration MIN_EVERY = Duration.ofMinutes(1);
	static final Duration MAX_EVERY = Duration.ofDays(30);
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
	static final Duration DEFAULT_KEEP = Duration.ofDays(7);
	static final Duration DEFAULT_RETRY = Duration.ofHours(1);
	static final int DEFAULT_MAX_VERIFIED = 1_000;
	static final int MAX_MAX_VERIFIED = 10_000;
	static final int MAX_DOMAINS = 10;
	static final int MAX_RULES = 10;

	/** {@code PluginContext.writeList}'s name rule. */
	static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	/**
	 * A domain a crawler's names are under: two labels at least - a lone {@code com} would confirm anyone who controls
	 * the reverse DNS of their own address.
	 */
	static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

	/** A guard rule's name as guard-rules.conf writes it: anything but a pipe, without spaces at the ends. */
	static final Pattern RULE = Pattern.compile("[^|\\s](?:[^|\\p{Cntrl}]{0,126}[^|\\s])?");

	private static final Set<String> KEYS = Set.of("feeds", "timeout", "keep", "retry", "max-verified");
	private static final Set<String> FEED_KEYS = Set.of("name", "url", "every", "rules", "domains");
	private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

	static FeederConfig defaults() {
		return new FeederConfig(defaultFeeds(), DEFAULT_TIMEOUT, DEFAULT_KEEP, DEFAULT_RETRY, DEFAULT_MAX_VERIFIED);
	}

	private static List<Feed> defaultFeeds() {
		return List.of(
				new Feed("googlebot", URI.create(GOOGLEBOT), DEFAULT_EVERY, List.of("googlebot-claim"),
						List.of("googlebot.com", "google.com")),
				new Feed("bingbot", URI.create(BINGBOT), DEFAULT_EVERY, List.of("bingbot-claim"), List.of("search.msn.com")),
				new Feed("applebot", URI.create(APPLEBOT), DEFAULT_EVERY, List.of("applebot-claim"),
						List.of("applebot.apple.com")));
	}

	/** Whether any feed checks claimants - whether the plugin observes anything. */
	boolean verifies() {
		return feeds.stream().anyMatch(Feed::verifies);
	}

	Feed feed(String name) {
		for (Feed feed : feeds)
			if (feed.name().equals(name))
				return feed;
		return null;
	}

	/** @throws PluginConfigException with every reason, at once */
	static FeederConfig parse(Config config) throws PluginConfigException {
		List<String> reasons = new ArrayList<>();
		for (String key : config.root().keySet())
			if (!KEYS.contains(key))
				reasons.add("unknown key '" + key + "' - crawler-feeder.conf has feeds, timeout, keep, retry and max-verified");

		Duration timeout = duration(config, "timeout", DEFAULT_TIMEOUT, Duration.ofSeconds(1), Duration.ofMinutes(5),
				"1s..5m", reasons);
		Duration keep = duration(config, "keep", DEFAULT_KEEP, Duration.ofHours(1), Duration.ofDays(90), "1h..90d", reasons);
		Duration retry = duration(config, "retry", DEFAULT_RETRY, Duration.ofMinutes(1), Duration.ofDays(7), "1m..7d",
				reasons);
		int maxVerified = DEFAULT_MAX_VERIFIED;
		if (config.hasPath("max-verified"))
		{
			try {
				maxVerified = config.getInt("max-verified");
				if (maxVerified < 1 || maxVerified > MAX_MAX_VERIFIED)
					reasons.add("max-verified must be 1.." + MAX_MAX_VERIFIED + ", not " + maxVerified);
			} catch (ConfigException e) {
				reasons.add("max-verified must be a number");
			}
		}

		List<Feed> feeds = defaultFeeds();
		if (config.hasPath("feeds"))
		{
			feeds = new ArrayList<>();
			ConfigValue value = config.getValue("feeds");
			if (value.valueType() != ConfigValueType.LIST)
				reasons.add("feeds must be a list of { name, url, every, domains, rules }");
			else
			{
				Set<String> names = new HashSet<>();
				int i = 0;
				for (ConfigValue entry : config.getList("feeds"))
				{
					i++;
					if (entry.valueType() != ConfigValueType.OBJECT)
					{
						reasons.add("feeds[" + i + "] must be an object { name, url, every, domains, rules }");
						continue;
					}
					Feed feed = feed(((ConfigObject) entry).toConfig(), i, reasons);
					if (null != feed && !names.add(feed.name()))
						reasons.add("feeds[" + i + "]: a second feed named '" + feed.name() + "' - each keeps a list of its own");
					else if (null != feed)
						feeds.add(feed);
				}
			}
		}

		if (!reasons.isEmpty())
			throw new PluginConfigException(reasons);
		return new FeederConfig(List.copyOf(feeds), timeout, keep, retry, maxVerified);
	}

	private static Duration duration(Config config, String key, Duration otherwise, Duration min, Duration max,
			String range, List<String> reasons) {
		if (!config.hasPath(key))
			return otherwise;
		try {
			Duration value = config.getDuration(key);
			if (value.compareTo(min) < 0 || value.compareTo(max) > 0)
				reasons.add(key + " must be " + range + ", not " + config.getValue(key).unwrapped());
			return value;
		} catch (ConfigException e) {
			reasons.add(key + " must be a duration, e.g. " + range.substring(range.indexOf('.') + 2));
			return otherwise;
		}
	}

	private static Feed feed(Config c, int i, List<String> reasons) {
		String at = "feeds[" + i + "]";
		int before = reasons.size();
		for (String key : c.root().keySet())
			if (!FEED_KEYS.contains(key))
				reasons.add(at + ": unknown key '" + key + "' - a feed has name, url, every, domains and rules");

		String name = null;
		if (!c.hasPath("name"))
			reasons.add(at + ": name is missing - the list it keeps, conf/lists/<name>.txt");
		else
		{
			name = c.getValue("name").unwrapped().toString();
			if (!NAME.matcher(name).matches())
				reasons.add(at + ": name '" + name + "' must match " + NAME.pattern());
		}

		URI url = null;
		if (c.hasPath("url"))
		{
			String text = c.getValue("url").unwrapped().toString();
			try {
				url = new URI(text);
				String scheme = (null == url.getScheme()) ? "" : url.getScheme().toLowerCase(Locale.ROOT);
				String host = (null == url.getHost()) ? null : url.getHost().toLowerCase(Locale.ROOT);
				if (null == host || null != url.getRawUserInfo())
					reasons.add(at + ": url must be an absolute https URL with a host and no user info");
				else if ("http".equals(scheme) && !LOOPBACK.contains(host))
					reasons.add(at + ": url must be https - plain http only to a loopback host");
				else if (!"https".equals(scheme) && !"http".equals(scheme))
					reasons.add(at + ": url must be https");
			} catch (Exception e) {
				reasons.add(at + ": url is not a URL");
			}
		}

		Duration every = DEFAULT_EVERY;
		if (c.hasPath("every"))
		{
			if (!c.hasPath("url"))
				reasons.add(at + ": every without url - there is nothing to fetch");
			try {
				every = c.getDuration("every");
				if (every.compareTo(MIN_EVERY) < 0 || every.compareTo(MAX_EVERY) > 0)
					reasons.add(at + ": every must be 1m..30d");
			} catch (ConfigException e) {
				reasons.add(at + ": every must be a duration, e.g. 24h");
			}
		}

		List<String> domains = strings(c, "domains", at, MAX_DOMAINS, reasons);
		for (int d = 0; d < domains.size(); d++)
		{
			String domain = domains.get(d).toLowerCase(Locale.ROOT);
			if (domain.endsWith("."))
				domain = domain.substring(0, domain.length() - 1);
			domains.set(d, domain);
			if (domain.length() > 253 || !DOMAIN.matcher(domain).matches())
				reasons.add(at + ": domains: '" + domains.get(d) + "' is not a domain of two labels or more,"
						+ " e.g. googlebot.com");
		}
		List<String> rules = strings(c, "rules", at, MAX_RULES, reasons);
		for (String rule : rules)
			if (!RULE.matcher(rule).matches())
				reasons.add(at + ": rules: '" + rule + "' is not a guard rule's name");

		if (!c.hasPath("url") && domains.isEmpty() && c.hasPath("name"))
			reasons.add(at + ": a feed needs a url to fetch, domains to check claimants against, or both");
		if (c.hasPath("rules") && domains.isEmpty())
			reasons.add(at + ": rules without domains - a claimant is checked against the domains");
		if (!domains.isEmpty() && !c.hasPath("rules") && null != name)
			rules = List.of(name + "-claim");
		if (!domains.isEmpty() && rules.isEmpty() && c.hasPath("rules"))
			reasons.add(at + ": rules is empty - name the guard rules that report a claimant, or leave it out for '"
					+ name + "-claim'");

		return (reasons.size() == before) ? new Feed(name, url, every, List.copyOf(rules), List.copyOf(domains)) : null;
	}

	/** A list of distinct strings (a single string is a list of one), at most {@code max}. */
	private static List<String> strings(Config c, String key, String at, int max, List<String> reasons) {
		if (!c.hasPath(key))
			return new ArrayList<>();
		ConfigValue value = c.getValue(key);
		List<String> out = new ArrayList<>();
		if (value.valueType() == ConfigValueType.STRING)
			out.add(value.unwrapped().toString().strip());
		else if (value.valueType() == ConfigValueType.LIST)
		{
			for (ConfigValue item : c.getList(key))
			{
				if (item.valueType() != ConfigValueType.STRING)
				{
					reasons.add(at + ": " + key + " must be a list of names");
					return new ArrayList<>();
				}
				out.add(item.unwrapped().toString().strip());
			}
		}
		else
		{
			reasons.add(at + ": " + key + " must be a list of names");
			return new ArrayList<>();
		}
		if (out.size() > max)
			reasons.add(at + ": " + key + " names more than " + max);
		return new ArrayList<>(new LinkedHashSet<>(out));
	}
}
