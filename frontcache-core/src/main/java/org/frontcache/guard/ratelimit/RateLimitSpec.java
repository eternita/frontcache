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
package org.frontcache.guard.ratelimit;

import org.frontcache.guard.GuardConfigException;

/**
 * The parsed form of a {@code rate:} predicate, computed once at load time.
 *
 * <pre>
 *   rate:&lt;limit&gt;/&lt;window&gt; [bucket=&lt;name&gt;] [scope=&lt;toplevel|all&gt;]
 *
 *   rate:100/10s                 100 requests per 10-second slot, per IP
 *   rate:10/s                    a bare unit means 1, so this is 10 per second
 *   rate:10/1m bucket=login      shares its counter with any other rule naming bucket 'login'
 * </pre>
 *
 * {@code bucket} defaults to the rule name, which is resolved by the rule parser - the predicate
 * factory does not know it. That default is deliberate: it makes a dry-run rule and a live rule
 * separate counters, so a stricter limit can be measured against real traffic while the current one
 * still enforces.
 */
public class RateLimitSpec {

	/** longest window accepted; beyond this a "rate limit" is really a quota and wants other tooling */
	static final long MAX_WINDOW_MILLIS = 3600000L;

	private final int limit;
	private final long windowMillis;
	private final String bucket;       // null until the rule parser binds the rule name
	private final boolean toplevelOnly;
	private final String source;       // the predicate exactly as written, for describe()

	private RateLimitSpec(int limit, long windowMillis, String bucket, boolean toplevelOnly, String source) {
		this.limit = limit;
		this.windowMillis = windowMillis;
		this.bucket = bucket;
		this.toplevelOnly = toplevelOnly;
		this.source = source;
	}

	/**
	 * @param arg  everything after "rate:"
	 * @param expr the whole predicate as written, used in messages and by describe()
	 */
	public static RateLimitSpec parse(String arg, String expr)
	{
		String[] tokens = arg.trim().split("\\s+");
		if (0 == tokens.length || 0 == tokens[0].length())
			throw new GuardConfigException("rate predicate needs <limit>/<window> in '" + expr + "'");

		int slash = tokens[0].indexOf('/');
		if (-1 == slash)
			throw new GuardConfigException("rate predicate needs <limit>/<window>, e.g. rate:100/10s, in '" + expr + "'");

		int limit = parseLimit(tokens[0].substring(0, slash).trim(), expr);
		long windowMillis = parseWindow(tokens[0].substring(slash + 1).trim(), expr);

		String bucket = null;
		boolean toplevelOnly = true;

		for (int i = 1; i < tokens.length; i++)
		{
			String token = tokens[i];
			if (0 == token.length())
				continue;

			int eq = token.indexOf('=');
			if (-1 == eq)
				throw new GuardConfigException("unknown rate attribute '" + token + "' in '" + expr + "' (expected name=value)");

			String name = token.substring(0, eq).trim();
			String value = token.substring(eq + 1).trim();

			switch (name)
			{
			case "bucket":
				if (0 == value.length())
					throw new GuardConfigException("bucket needs a name in '" + expr + "'");
				bucket = value;
				break;

			case "scope":
				if ("toplevel".equals(value))
					toplevelOnly = true;
				else if ("all".equals(value))
					toplevelOnly = false;
				else
					throw new GuardConfigException("unknown scope '" + value + "' in '" + expr + "' (expected toplevel or all)");
				break;

			default:
				throw new GuardConfigException("unknown rate attribute '" + name + "' in '" + expr + "'");
			}
		}

		return new RateLimitSpec(limit, windowMillis, bucket, toplevelOnly, expr);
	}

	private static int parseLimit(String text, String expr)
	{
		int limit;
		try {
			limit = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			throw new GuardConfigException("'" + text + "' is not a request count in '" + expr + "'");
		}

		if (1 > limit)
			throw new GuardConfigException("rate limit must be at least 1 in '" + expr + "'");

		// above this the packed counter saturates, so over-limit could never be detected
		if (WindowLimiter.MAX_LIMIT < limit)
			throw new GuardConfigException("rate limit must be at most " + WindowLimiter.MAX_LIMIT + " in '" + expr + "'");

		return limit;
	}

	/**
	 * {@code 10s}, {@code 1m}, {@code 2h} - and a bare unit means 1, so {@code s} is {@code 1s}.
	 */
	private static long parseWindow(String text, String expr)
	{
		if (0 == text.length())
			throw new GuardConfigException("rate predicate needs a window, e.g. rate:100/10s, in '" + expr + "'");

		char unit = text.charAt(text.length() - 1);
		String amount = text.substring(0, text.length() - 1).trim();

		long multiplier;
		switch (unit)
		{
		case 's':
			multiplier = 1000L;
			break;
		case 'm':
			multiplier = 60000L;
			break;
		case 'h':
			multiplier = 3600000L;
			break;
		default:
			throw new GuardConfigException("rate window unit must be s, m or h in '" + expr + "'");
		}

		long count = 1;
		if (0 < amount.length())
		{
			try {
				count = Long.parseLong(amount);
			} catch (NumberFormatException e) {
				throw new GuardConfigException("'" + amount + "' is not a window length in '" + expr + "'");
			}
		}

		if (1 > count)
			throw new GuardConfigException("rate window must be at least 1" + unit + " in '" + expr + "'");

		long windowMillis = count * multiplier;
		if (MAX_WINDOW_MILLIS < windowMillis)
			throw new GuardConfigException("rate window must be at most 1h in '" + expr + "'");

		return windowMillis;
	}

	/**
	 * @return a copy with the bucket resolved, called by the rule parser once the rule name is known
	 */
	public RateLimitSpec withDefaultBucket(String ruleName) {
		return (null != bucket) ? this : new RateLimitSpec(limit, windowMillis, ruleName, toplevelOnly, source);
	}

	public int getLimit() {
		return limit;
	}

	public long getWindowMillis() {
		return windowMillis;
	}

	public String getBucket() {
		return bucket;
	}

	public boolean isToplevelOnly() {
		return toplevelOnly;
	}

	public String getSource() {
		return source;
	}

}
