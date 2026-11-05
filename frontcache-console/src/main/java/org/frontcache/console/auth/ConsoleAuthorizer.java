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
 * What a principal may do, as of 2.12.0. A console plugin provides one as a bean of its {@code rootComponents()};
 * without one, every authenticated principal may do everything - the single-user console's rule.
 *
 * It narrows, never widens: {@code config-editing = false}, {@code edge-editing = false} and the plugin's own
 * switches still turn a feature off for everyone, whatever this answers.
 */
public interface ConsoleAuthorizer {

	/**
	 * @param who        the request's principal
	 * @param permission a {@link ConsolePermissions} id, or a plugin's own
	 * @param edgeURL    the edge the request acts on, as the request named it, or null when it acts on no single
	 *                   edge. Whether the edge is one this principal may see at all is {@link ConsoleEdgeScope}'s
	 *                   answer, checked separately - this is for a permission granted on some edges only
	 */
	boolean permits(ConsolePrincipal who, String permission, String edgeURL);

	/**
	 * What {@link ConsoleSecurity} asks, with the request's scope - for an authorizer whose answer depends on which of
	 * a principal's edge sets the request is in (a role per tenant). The default ignores the scope.
	 *
	 * <b>The same answer for an edge outside {@code scope} as for one that does not exist</b>: a refusal that differs
	 * would tell a principal which edges exist beyond what it may see.
	 *
	 * @param scope the request's scope, never null
	 */
	default boolean permits(ConsolePrincipal who, String permission, String edgeURL, EdgeScope scope) {
		return permits(who, permission, edgeURL);
	}
}
