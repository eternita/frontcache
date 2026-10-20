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
 * Replaces {@code com.netflix.hystrix.exception.HystrixBadRequestException}: the caller asked for
 * something invalid, so the failure says nothing about the health of the dependency.
 *
 * Thrown out of {@link FcCommand#execute()} unchanged - no fallback is resolved - and excluded from
 * the circuit breaker's failure rate via {@code ignoreExceptions}.
 */
public class FcBadRequestException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public FcBadRequestException(String message) {
		super(message);
	}

	public FcBadRequestException(String message, Throwable cause) {
		super(message, cause);
	}
}
