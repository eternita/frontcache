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
package org.frontcache.io;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.frontcache.FrontCacheEngine;
import org.frontcache.bots.BotClassifier;

/**
 * Dry-parses a candidate config file before {@code set-config} writes it.
 *
 * <h2>Why validate at all, rather than write and let the reload cope</h2>
 *
 * Every one of these loaders is deliberately tolerant: a line it cannot use is logged and skipped,
 * and the rest of the file keeps working. That is right for startup - a node must come up - and it is
 * exactly wrong as the only feedback an operator gets from a Save button, because the outcome is a
 * node running configuration that does not match its own config file. That mismatch is what the
 * Configs screens exist to make visible, and this is the same problem one step earlier.
 *
 * The cost of getting it wrong is on record: {@code GuardRuleParser.splitFields} documents a
 * production node where the naive {@code split("\\|")} silently disabled 5 of 19 rules - every
 * anti-scraper redirect - and nothing said so.
 *
 * <h2>A parse error refuses the whole save</h2>
 *
 * All of it, not just the bad line, and every bad line is listed so they can be fixed in one pass.
 * This does mean a file that <b>already</b> contains a broken line cannot be saved until that line is
 * fixed too. That is deliberate: the operator is right there, the message names the line, and the
 * alternative is a Save button that quietly keeps a rule dead.
 *
 * <h2>Nothing here touches live state</h2>
 *
 * No rule swap, no resolver reload, no counter reset, no pool. Everything parsed is thrown away.
 */
public class ConfigFileValidator {

	private ConfigFileValidator() { }

	/**
	 * @return one message per unusable line, {@code "line N: ..."}; empty when the file is usable
	 */
	public static List<String> validate(ConfigFile configFile, String content)
	{
		switch (configFile)
		{
		case RESILIENCE:
			return validateProperties(content);

		case GUARD_RULES:
			return validateGuardRules(content);

		case BOTS:
			return BotClassifier.validate(content);

		case DYNAMIC_URLS:
			return validateDynamicURLs(content);

		case FALLBACKS:
			return validateFallbacks(content);
		}

		return new ArrayList<String>();
	}

	/**
	 * A properties file is validated by loading it, and by nothing else.
	 *
	 * Unknown keys are <b>not</b> an error: the file legitimately carries the legacy
	 * {@code hystrix.command.*} / {@code hystrix.threadpool.*} prefixes, keys for command names this
	 * node has never used, and keys a newer node would understand. Rejecting those would refuse the
	 * exact files {@code FcResilienceConfig.COMMAND_KEY_INHERITANCE} exists to keep working.
	 *
	 * What {@code Properties.load} does catch is the one thing that silently loses settings - a
	 * malformed escape sequence.
	 */
	private static List<String> validateProperties(String content)
	{
		List<String> errors = new ArrayList<String>();

		try {
			Properties properties = new Properties();
			properties.load(new InputStreamReader(
					new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
		} catch (Exception e) {
			errors.add("not a readable properties file: " + e.getMessage());
		}

		return errors;
	}

	/**
	 * Guard rules are parsed by the live engine, so the parser, the predicate factory and the
	 * redirect-host allowlist are the ones the node will actually apply.
	 *
	 * When no engine exists yet there is nothing to validate against and no rules are running either,
	 * so the file is accepted - a node that has not served a request is not a node whose config can be
	 * mismatched.
	 */
	private static List<String> validateGuardRules(String content)
	{
		FrontCacheEngine engine = FrontCacheEngine.existingInstance();

		if (null == engine || null == engine.getGuardRuleEngine())
			return new ArrayList<String>();

		return engine.getGuardRuleEngine().validate(content);
	}

	/**
	 * One regex per non-comment line, the same read {@code FCConfig.loadCacheIgnoreURIPatterns} does.
	 *
	 * A bad pattern IS an error here even though the loader only logs it at info and skips it: a
	 * skipped pattern means a URL that must never be cached is cacheable, which is the most expensive
	 * silent outcome in this whole set of files.
	 */
	private static List<String> validateDynamicURLs(String content)
	{
		List<String> errors = new ArrayList<String>();

		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++)
		{
			String trimmed = lines[i].trim();
			if (0 == trimmed.length() || trimmed.startsWith("#"))
				continue;

			try {
				// The loader compiles the RAW line, not the trimmed one - a pattern with significant
				// leading whitespace is unlikely but this is not the place to change its meaning.
				Pattern.compile(lines[i]);
			} catch (Exception e) {
				errors.add("line " + (i + 1) + ": not a valid regular expression - " + e.getMessage());
			}
		}

		return errors;
	}

	/**
	 * {@code <url pattern> <file> [init url]} - the split
	 * {@code FileBasedFallbackResolver.loadFallbackConfigStr} does, plus the regex compile it does on
	 * the first field.
	 */
	private static List<String> validateFallbacks(String content)
	{
		List<String> errors = new ArrayList<String>();

		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++)
		{
			String trimmed = lines[i].trim();
			if (0 == trimmed.length() || trimmed.startsWith("#"))
				continue;

			String[] fields = trimmed.split(" ");
			if (2 != fields.length && 3 != fields.length)
			{
				errors.add("line " + (i + 1) + ": expected '<url pattern> <file> [init url]'"
						+ " (2 or 3 space-separated words) but got " + fields.length);
				continue;
			}

			try {
				Pattern.compile(fields[0]);
			} catch (Exception e) {
				errors.add("line " + (i + 1) + ": the URL pattern is not a valid regular expression - " + e.getMessage());
			}
		}

		return errors;
	}
}
