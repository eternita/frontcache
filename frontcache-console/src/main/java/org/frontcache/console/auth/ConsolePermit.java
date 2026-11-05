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
 * The permission a console handler method needs, as of 2.12.0 - checked by {@link ConsolePermissionInterceptor}
 * before the handler runs, through {@link ConsoleAuthorizer}. See {@link ConsolePermissions} for the core's ids and
 * for the rule that every POST carries one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConsolePermit {

	/** A {@link ConsolePermissions} id, or a plugin's own. */
	String value();

	/**
	 * The request parameter naming the edge the handler acts on, handed to {@link ConsoleAuthorizer} - so that an
	 * authorizer can grant a permission on some edges and not others. Empty when the handler acts on no single edge.
	 */
	String edgeParameter() default "edge";
}
