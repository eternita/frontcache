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
package org.frontcache.guard;

import org.frontcache.core.RequestContext;
import org.frontcache.plugin.host.PluginPredicates;

/**
 * {@code ext:<name>[:<argument>]} - a condition a started plugin offers ({@code decide}, E3). Parsed here into its name
 * and argument only: the rules load before the plugins start, and the console and the portal check files with no
 * plugin at all. The plugin compiles the argument when the predicate is first bound after the plugins (re)start - see
 * {@link PluginPredicates}.
 *
 * <p>When it has no answer - no started plugin offers the name, the plugin refused the argument, threw, or is switched
 * off for going over its budget - {@link #test} throws {@link PredicateUndecided} and the rule does not match.
 */
public final class ExtPredicate implements GuardPredicate {

	public static final String PREFIX = "ext:";

	/** A predicate's name, as a plugin's provider names it. */
	public static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,30}");

	private final String name;
	private final String argument;
	private final String described;

	/** What {@link PluginPredicates} bound this predicate to - replaced when the plugins (re)start. */
	private volatile Object binding;

	ExtPredicate(String name, String argument, String described) {
		this.name = name;
		this.argument = argument;
		this.described = described;
	}

	/**
	 * @param arg what follows {@code ext:}: {@code <name>} or {@code <name>:<argument>}
	 * @throws GuardConfigException when the name is not one
	 */
	static ExtPredicate parse(String arg, String expr) {
		int colon = arg.indexOf(':');
		String name = ((-1 < colon) ? arg.substring(0, colon) : arg).trim();
		String argument = (-1 < colon) ? arg.substring(colon + 1).trim() : "";
		if (0 == name.length())
			throw new GuardConfigException("ext predicate needs a name in '" + expr + "' - ext:<name>:<argument>, as a"
					+ " started plugin offers it");
		if (!NAME.matcher(name).matches())
			throw new GuardConfigException("'" + name + "' is not a plugin predicate's name in '" + expr + "' - lower case"
					+ " letters, digits and '-', starting with a letter");
		return new ExtPredicate(name, argument, PREFIX + name + (argument.isEmpty() ? "" : ":" + argument));
	}

	public String getName() {
		return name;
	}

	public String getArgument() {
		return argument;
	}

	@Override
	public boolean test(RequestContext context) {
		return PluginPredicates.test(this, context);
	}

	@Override
	public ExtPredicate pluginPredicate() {
		return this;
	}

	@Override
	public String describe() {
		return described;
	}

	/** For {@link PluginPredicates} only. */
	public Object binding() {
		return binding;
	}

	/** For {@link PluginPredicates} only. */
	public void binding(Object binding) {
		this.binding = binding;
	}
}
