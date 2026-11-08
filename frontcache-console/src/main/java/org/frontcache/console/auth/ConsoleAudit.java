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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How a state-changing handler appears in the audit log, as of 2.12.0 - see {@link ConsoleAuditor}. Optional: every
 * non-GET handler is recorded with or without it, as its {@link ConsolePermit} id and its edge parameter.
 *
 * <b>{@link #parameters()} is the only way a request parameter reaches the log</b>, so it names identifiers - an edge,
 * a filter, a list's name, a mode - and never a field that may hold a secret or content ({@code password},
 * {@code content}, a list body). {@code ConsolePermitTest} refuses those names.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConsoleAudit {

	/** The event's type - {@code cache.purge}, {@code warmer.start}. Empty: the handler's permission id. */
	String value() default "";

	/** The request parameters recorded with the event, beside the permit's edge parameter (always recorded). */
	String[] parameters() default {};
}
