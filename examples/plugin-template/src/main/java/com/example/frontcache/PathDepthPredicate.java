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
package com.example.frontcache;

import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;

/**
 * E3: {@code ext:path-depth:<op><n>} - how many segments the request's path has. {@code /a/b/c} is 3, {@code /} is 0,
 * and empty segments do not count ({@code /a//b} is 2). A deep-path scanner, in a guard rule:
 *
 * <pre>
 * too-deep | ext:path-depth:&gt;12 | reject:404
 * </pre>
 *
 * Everything a predicate must be: compiled once per rule ({@link #parse}), then {@code test} on the request thread -
 * cheap, allocation-free, from memory, and answering (false) for a view with nothing behind it.
 */
final class PathDepthPredicate implements GuardPredicateProvider {

	static final String NAME = "path-depth";

	private static final Pattern ARGUMENT = Pattern.compile("(>=|<=|>|<|=)\\s*(\\d{1,4})");

	private final int maxDepth;

	/** @param maxDepth the largest depth a rule may name - {@code max-depth} in example.conf */
	PathDepthPredicate(int maxDepth) {
		this.maxDepth = maxDepth;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public PluginPredicate parse(String argument) throws PluginConfigException {
		Matcher m = ARGUMENT.matcher((null == argument) ? "" : argument.trim());
		if (!m.matches())
			throw new PluginConfigException("ext:" + NAME + ": expected a comparison and a depth - '>4', '<=2', '=0' -"
					+ " not '" + argument + "'");
		String op = m.group(1);
		int n = Integer.parseInt(m.group(2));
		if (n > maxDepth)
			throw new PluginConfigException("ext:" + NAME + ": depth " + n + " is over max-depth " + maxDepth);
		IntPredicate compare = switch (op) {
			case ">" -> d -> d > n;
			case ">=" -> d -> d >= n;
			case "<" -> d -> d < n;
			case "<=" -> d -> d <= n;
			default -> d -> d == n;
		};
		return request -> {
			int depth = depth(request.uri());
			return depth >= 0 && compare.test(depth);
		};
	}

	/** The number of non-empty segments of a path; -1 for none at all, which matches no comparison. */
	static int depth(String path) {
		if (null == path)
			return -1;
		int depth = 0;
		boolean inSegment = false;
		for (int i = 0; i < path.length(); i++)
		{
			if ('/' == path.charAt(i))
				inSegment = false;
			else if (!inSegment)
			{
				inSegment = true;
				depth++;
			}
		}
		return depth;
	}
}
