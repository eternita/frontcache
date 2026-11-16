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

/**
 * One compiled {@code ext:<name>:<argument>} condition - see {@link GuardPredicateProvider}.
 */
@FunctionalInterface
public interface PluginPredicate {

	/**
	 * Whether the condition holds for the request. Runs on the request thread, for every request that reaches the
	 * rule, timed against the plugin's budget: <b>cheap, pure, from memory</b>. Every value of the view may be null -
	 * a synthetic probe the node runs at load has no request behind it - and a predicate must answer for it rather than
	 * throw. A throw is not {@code false}: the rule it is in does not match.
	 */
	boolean test(RequestView request);
}
