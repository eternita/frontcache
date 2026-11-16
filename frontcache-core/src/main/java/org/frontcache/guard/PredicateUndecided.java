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

/**
 * Thrown by a predicate that has no answer for this request - a plugin's {@code ext:} predicate that threw, is switched
 * off for going over its budget, or that no started plugin offers. It is not {@code false}: negation passes it on
 * untouched, and the rule it is in ({@link GuardRule#matches}, {@code BotRule.matches}) does not match. So an
 * {@code !ext:} predicate that fails is never "true".
 *
 * <p>One shared instance without a stack trace: it is thrown on the request thread, possibly for every request.
 */
public final class PredicateUndecided extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final PredicateUndecided INSTANCE = new PredicateUndecided();

	private PredicateUndecided() {
		super("undecided", null, false, false);
	}
}
