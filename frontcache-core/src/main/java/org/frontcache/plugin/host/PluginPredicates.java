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
package org.frontcache.plugin.host;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.core.RequestContext;
import org.frontcache.guard.ExtPredicate;
import org.frontcache.guard.PredicateUndecided;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code ext:} predicates the started plugins offer ({@code decide}, E3), and how a rule's {@link ExtPredicate} is
 * answered by one.
 *
 * <h2>Binding</h2>
 *
 * The rules load before the plugins start and may be reloaded while they run, so an {@code ext:} predicate is bound
 * late: the first time it is asked after the plugins (re)started - or when the engine checks the rules right after
 * starting them - its plugin's {@link GuardPredicateProvider#parse} compiles the argument, once per
 * {@linkplain #generation() generation} of the started set. A name no started plugin offers, or an argument the plugin
 * refuses, is an ERROR (once per predicate and generation) and a predicate with no answer.
 *
 * <h2>No answer is not false</h2>
 *
 * A predicate with no answer - unbound, refused, threw, or its plugin {@linkplain DecideBudget switched off} - throws
 * {@link PredicateUndecided}, and the rule it is in does not match (negated or not). The core decides what a failure
 * means, never the plugin.
 */
public final class PluginPredicates {

	private static final Logger logger = LoggerFactory.getLogger(PluginPredicates.class);

	/** One offered name: who offers it, and the budget its calls are timed against. */
	record Provider(String name, String plugin, GuardPredicateProvider provider, DecideBudget budget) {
	}

	/** What a predicate was bound to in one generation: a compiled predicate, or why there is none. */
	private record Binding(long generation, Provider provider, PluginPredicate predicate, String refusal) {
	}

	private static volatile Map<String, Provider> providers = Map.of();
	private static volatile long generation = 0;
	private static volatile boolean running = false;

	private PluginPredicates() {
	}

	/** The plugins started (or restarted): what they offer replaces what was offered, and every predicate rebinds. */
	static synchronized void install(Map<String, Provider> offered) {
		providers = Collections.unmodifiableMap(new LinkedHashMap<>(offered));
		running = true;
		generation++;
	}

	/** A plugin started late - its config fixed by a reload: its names join the offered ones. */
	static synchronized void add(Map<String, Provider> offered) {
		Map<String, Provider> next = new LinkedHashMap<>(providers);
		for (Map.Entry<String, Provider> e : offered.entrySet())
			next.putIfAbsent(e.getKey(), e.getValue());
		providers = Collections.unmodifiableMap(next);
		generation++;
	}

	/** The plugins stopped: nothing is offered. */
	static synchronized void clear() {
		providers = Map.of();
		running = false;
		generation++;
	}

	/** Whether the plugins have started - before, an unbound predicate is not an error yet. */
	public static boolean isRunning() {
		return running;
	}

	static long generation() {
		return generation;
	}

	/** The names offered now, with the plugin offering each. */
	static Map<String, String> offered() {
		Map<String, String> names = new LinkedHashMap<>();
		for (Provider p : providers.values())
			names.put(p.name(), p.plugin());
		return names;
	}

	/**
	 * {@link ExtPredicate#test}: asks the plugin, timed against its budget.
	 *
	 * @throws PredicateUndecided when there is no answer
	 */
	public static boolean test(ExtPredicate predicate, RequestContext context) {

		Binding binding = bound(predicate, null);
		PluginPredicate compiled = binding.predicate();
		if (null == compiled)
			throw PredicateUndecided.INSTANCE;

		DecideBudget budget = binding.provider().budget();
		if (!budget.admits())
			throw PredicateUndecided.INSTANCE;

		CoreRequestView view = CoreRequestView.of(context);
		long start = DecideBudget.nanoClock.getAsLong();
		boolean answer;
		try {
			answer = compiled.test(view);
		} catch (Throwable t) {
			budget.failed(DecideBudget.nanoClock.getAsLong() - start, t, predicate.describe());
			throw PredicateUndecided.INSTANCE;
		}
		budget.record(DecideBudget.nanoClock.getAsLong() - start);
		return answer;
	}

	/**
	 * Binds a rule's predicate now - what the engine does for every rule right after the plugins start and after each
	 * reload of guard-rules.conf or bots.conf, so a mistake is in the log at once rather than at the first request.
	 *
	 * @param rule the rule's name and file, for the log line
	 * @return null when it is bound; else why it has no answer
	 */
	public static String check(ExtPredicate predicate, String rule) {
		Binding binding = bound(predicate, rule);
		return binding.refusal();
	}

	/**
	 * What {@code set-config} asks of a guard-rules.conf it is about to write: whether a started plugin offers the
	 * name and accepts the argument. Binds nothing. Null when it would bind, or when no plugin has started yet.
	 */
	public static String validate(ExtPredicate predicate) {
		if (!running)
			return null;
		Provider provider = providers.get(predicate.getName());
		if (null == provider)
			return "no started plugin offers '" + ExtPredicate.PREFIX + predicate.getName() + "'" + offeredNames();
		try {
			if (null == provider.provider().parse(predicate.getArgument()))
				return "plugin '" + provider.plugin() + "' compiled '" + predicate.describe() + "' to nothing";
			return null;
		} catch (PluginConfigException e) {
			return "plugin '" + provider.plugin() + "' refuses '" + predicate.describe() + "': " + reasons(e.reasons());
		} catch (Throwable t) {
			return "plugin '" + provider.plugin() + "' threw parsing '" + predicate.describe() + "': " + t;
		}
	}

	private static Binding bound(ExtPredicate predicate, String rule) {
		Object current = predicate.binding();
		long now = generation;
		if (current instanceof Binding b && b.generation() == now)
			return b;
		synchronized (predicate) {
			current = predicate.binding();
			if (current instanceof Binding b && b.generation() == generation)
				return b;
			Binding b = bind(predicate, rule);
			predicate.binding(b);
			return b;
		}
	}

	private static Binding bind(ExtPredicate predicate, String rule) {

		long gen = generation;
		String where = (null == rule) ? "" : " (rule '" + rule + "')";
		Provider provider = providers.get(predicate.getName());
		if (null == provider) {
			String refusal = "no started plugin offers '" + ExtPredicate.PREFIX + predicate.getName() + "'" + offeredNames();
			if (running)
				logger.error("Predicate '{}'{} has no answer - the rules naming it do not match: {}", predicate.describe(),
						where, refusal);
			return new Binding(gen, null, null, refusal);
		}

		try {
			PluginPredicate compiled = provider.provider().parse(predicate.getArgument());
			if (null == compiled)
				throw new PluginConfigException(List.of("parse returned nothing"));
			logger.info("Predicate '{}'{} is answered by plugin '{}'", predicate.describe(), where, provider.plugin());
			return new Binding(gen, provider, compiled, null);
		} catch (PluginConfigException e) {
			String refusal = "plugin '" + provider.plugin() + "' refuses '" + predicate.describe() + "': " + reasons(e.reasons());
			logger.error("Predicate '{}'{} has no answer - the rules naming it do not match: {}", predicate.describe(), where,
					refusal);
			return new Binding(gen, provider, null, refusal);
		} catch (Throwable t) {
			provider.budget().failed(0, t, predicate.describe());
			String refusal = "plugin '" + provider.plugin() + "' threw parsing '" + predicate.describe() + "': " + t;
			logger.error("Predicate '" + predicate.describe() + "'" + where + " has no answer - the rules naming it do not match", t);
			return new Binding(gen, provider, null, refusal);
		}
	}

	private static String offeredNames() {
		Map<String, Provider> now = providers;
		return now.isEmpty() ? " (none is offered)" : " (offered: " + String.join(", ", now.keySet()) + ")";
	}

	private static String reasons(List<String> reasons) {
		return (null == reasons || reasons.isEmpty()) ? "refused, with no reason given" : String.join("; ", reasons);
	}
}
