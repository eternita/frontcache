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
package org.frontcache.guard;

/**
 * A guard rule could not be parsed or violates a safety rail (bad regex, unknown predicate,
 * redirect target outside the allowed hosts, self-redirect loop).
 *
 * Always handled by skipping that one rule with an error in the log - a typo in guard-rules.conf
 * must never take the edge down.
 */
@SuppressWarnings("serial")
public class GuardConfigException extends RuntimeException {

	public GuardConfigException(String message) {
		super(message);
	}

	public GuardConfigException(String message, Throwable cause) {
		super(message, cause);
	}

}
