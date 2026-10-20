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

/**
 * One condition of a guard rule (e.g. "uri~^/admin/", "!cookie:hruc").
 *
 * Implementations run on the request thread for every request, before cache and origin, so they
 * must be cheap and must never throw: a predicate that cannot decide returns false.
 */
public interface GuardPredicate {

	/**
	 * @param context request context - may carry a null servlet request (async include, or a
	 *                synthetic probe context used for startup loop detection)
	 * @return true when this condition holds for the request
	 */
	boolean test(RequestContext context);

	/**
	 * @return the predicate as written in guard-rules.conf (used by logs and the admin API)
	 */
	String describe();

}
