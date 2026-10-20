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
package org.frontcache.resilience;

/**
 * Replaces {@code com.netflix.hystrix.exception.HystrixRuntimeException}: thrown out of
 * {@link FcCommand#execute()} when the command failed AND the fallback could not supply a value.
 *
 * Carries the command key and the outcome that caused it so callers can react without string
 * matching - {@code ConcurrentIncludeProcessor} is the one that does.
 */
public class FcCommandException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String commandKey;
	private final FcEventType failureType;

	public FcCommandException(String commandKey, FcEventType failureType, String message, Throwable cause) {
		super(message, cause);
		this.commandKey = commandKey;
		this.failureType = failureType;
	}

	public String getCommandKey() {
		return commandKey;
	}

	/** The outcome that triggered the fallback attempt, e.g. TIMEOUT or SHORT_CIRCUITED. */
	public FcEventType getFailureType() {
		return failureType;
	}
}
