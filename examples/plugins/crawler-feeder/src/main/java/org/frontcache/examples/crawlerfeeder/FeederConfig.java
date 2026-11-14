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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.frontcache.plugin.PluginConfigException;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * {@code conf/crawler-feeder.conf}, read whole or refused whole. Absent (or without {@code feeds}) it is Google's and
 * Bing's published crawler ranges, refreshed daily:
 *
 * <pre>
 * feeds = [
 *   { name = googlebot, url = "https://developers.google.com/static/search/apis/ipranges/googlebot.json", every = 24h }
 *   { name = bingbot,   url = "https://www.bing.com/toolbox/bingbot.json", every = 24h }
 * ]
 * # connecting and the whole answer, each
 * timeout = 30s
 * </pre>
 *
 * Each feed keeps {@code conf/lists/<name>.txt}; a rule reads it as {@code client-ip:@lists/<name>.txt}.
 */
record FeederConfig(List<Feed> feeds, Duration timeout) {

	/** One published list: where it is, which list it keeps, how often. */
	record Feed(String name, URI url, Duration every) {
	}

	static final String GOOGLEBOT = "https://developers.google.com/static/search/apis/ipranges/googlebot.json";
	static final String BINGBOT = "https://www.bing.com/toolbox/bingbot.json";
	static final Duration DEFAULT_EVERY = Duration.ofHours(24);
	static final Duration MIN_EVERY = Duration.ofMinutes(1);
	static final Duration MAX_EVERY = Duration.ofDays(30);
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	/** {@code PluginContext.writeList}'s name rule. */
	static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	private static final Set<String> KEYS = Set.of("feeds", "timeout");
	private static final Set<String> FEED_KEYS = Set.of("name", "url", "every");
	private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

	static FeederConfig defaults() {
		return new FeederConfig(List.of(new Feed("googlebot", URI.create(GOOGLEBOT), DEFAULT_EVERY),
				new Feed("bingbot", URI.create(BINGBOT), DEFAULT_EVERY)), DEFAULT_TIMEOUT);
	}

	/** @throws PluginConfigException with every reason, at once */
	static FeederConfig parse(Config config) throws PluginConfigException {
		List<String> reasons = new ArrayList<>();
		for (String key : config.root().keySet())
			if (!KEYS.contains(key))
				reasons.add("unknown key '" + key + "' - crawler-feeder.conf has feeds and timeout");

		Duration timeout = DEFAULT_TIMEOUT;
		if (config.hasPath("timeout"))
		{
			try {
				timeout = config.getDuration("timeout");
				if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(5)) > 0)
					reasons.add("timeout must be 1s..5m, not " + config.getValue("timeout").unwrapped());
			} catch (ConfigException e) {
				reasons.add("timeout must be a duration, e.g. 30s");
			}
		}

		List<Feed> feeds = defaults().feeds();
		if (config.hasPath("feeds"))
		{
			feeds = new ArrayList<>();
			ConfigValue value = config.getValue("feeds");
			if (value.valueType() != ConfigValueType.LIST)
				reasons.add("feeds must be a list of { name, url, every }");
			else
			{
				Set<String> names = new HashSet<>();
				int i = 0;
				for (ConfigValue entry : config.getList("feeds"))
				{
					i++;
					if (entry.valueType() != ConfigValueType.OBJECT)
					{
						reasons.add("feeds[" + i + "] must be an object { name, url, every }");
						continue;
					}
					Feed feed = feed(((com.typesafe.config.ConfigObject) entry).toConfig(), i, reasons);
					if (null != feed && !names.add(feed.name()))
						reasons.add("feeds[" + i + "]: a second feed named '" + feed.name() + "' - each keeps a list of its own");
					else if (null != feed)
						feeds.add(feed);
				}
			}
		}

		if (!reasons.isEmpty())
			throw new PluginConfigException(reasons);
		return new FeederConfig(List.copyOf(feeds), timeout);
	}

	private static Feed feed(Config c, int i, List<String> reasons) {
		String at = "feeds[" + i + "]";
		int before = reasons.size();
		for (String key : c.root().keySet())
			if (!FEED_KEYS.contains(key))
				reasons.add(at + ": unknown key '" + key + "' - a feed has name, url and every");

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
		if (!c.hasPath("url"))
			reasons.add(at + ": url is missing");
		else
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
			try {
				every = c.getDuration("every");
				if (every.compareTo(MIN_EVERY) < 0 || every.compareTo(MAX_EVERY) > 0)
					reasons.add(at + ": every must be 1m..30d");
			} catch (ConfigException e) {
				reasons.add(at + ": every must be a duration, e.g. 24h");
			}
		}
		return (reasons.size() == before) ? new Feed(name, url, every) : null;
	}
}
