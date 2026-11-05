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
package org.frontcache.console.auth;

/**
 * A sign-in refused before its password was compared, because this user has failed too often - thrown by a
 * {@link ConsoleIdentityProvider}, as of 2.12.0. The login form answers it with {@code 429} and a message saying to
 * wait; a Basic request with {@code 429} and {@code Retry-After}. Never with the wrong-password answer: an operator who
 * types the right password during a lockout must be told why it did not work.
 *
 * An identity provider that throttles should count failures for every submitted name, configured or not, so that the
 * {@code 429} says nothing about which names exist.
 */
public class ConsoleSignInThrottledException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long retryAfterSeconds;

	public ConsoleSignInThrottledException(long retryAfterSeconds) {
		super("Too many failed sign-ins - try again in " + Math.max(1, retryAfterSeconds) + " seconds");
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	/** For the {@code Retry-After} header; at least 1. */
	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
