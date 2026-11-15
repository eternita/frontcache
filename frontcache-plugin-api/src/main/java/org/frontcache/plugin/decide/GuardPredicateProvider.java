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
package org.frontcache.plugin.decide;

import org.frontcache.plugin.PluginConfigException;

/**
 * {@link org.frontcache.plugin.PluginKind#DECIDE}: a named condition a guard rule ({@code guard-rules.conf}) or a
 * bot rule ({@code bots.conf}) can use, written {@code ext:<name>:<argument>} - negatable as {@code !ext:...} like
 * every predicate:
 *
 * <pre>
 * eu-only-checkout | uri~^/checkout ; !ext:geo:country=DE,FR,PL | reject:403
 * </pre>
 *
 * <h2>The core decides what a failure means</h2>
 *
 * A {@link PluginPredicate} that throws, or one switched off for going over its budget, does not answer - and the
 * <b>rule</b> it is in then does not match that request, whichever way it is negated. For a reject, redirect or
 * challenge rule that is fail-open; for an {@code allow} rule it means the exemption does not apply, which is why an
 * {@code allow} rule naming an {@code ext:} predicate loads only with {@code | on-error=allow} written in it.
 *
 * <h2>The budget</h2>
 *
 * Every call is timed. Over a minute, a plugin whose predicates' p99 is over its budget
 * ({@code front-cache.plugins.<id>.decide-budget}, default 200 microseconds) or that throws on more than 1 % of calls
 * is switched off for five minutes: its rules stop matching, and the node says so. A predicate cannot be interrupted
 * mid-call - <b>a predicate that does I/O on the request thread is a bug</b>: refresh data in the background (the
 * plugin's {@link org.frontcache.plugin.PluginContext#scheduler() scheduler}) and answer from memory.
 */
public interface GuardPredicateProvider {

	/**
	 * The name rules use: {@code geo} for {@code ext:geo:...}. Lower case letters, digits and {@code -}, starting with
	 * a letter, at most 31 characters. Two started plugins offering one name: the first one loaded wins, with a WARN.
	 */
	String name();

	/**
	 * Compiles a rule's argument - what follows {@code ext:<name>:}, trimmed, possibly empty - once per rule, after the
	 * rules load or the plugin starts, never per request.
	 *
	 * @return the predicate the rule then asks on every request it reaches
	 * @throws PluginConfigException when the argument is not one this predicate understands: the rule never matches,
	 *                               and the node logs every reason as an ERROR
	 */
	PluginPredicate parse(String argument) throws PluginConfigException;
}
