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

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Who a console request is from, as of 2.12.0: what {@link ConsoleIdentityProvider} concluded at sign-in, kept in
 * the session (or concluded again from a Basic header on every script request) and set on every request the
 * {@code ConsoleAuthFilter} lets through, as the {@link #REQUEST_ATTRIBUTE} attribute.
 *
 * The core reads {@link #name()} (the header) and hands the whole principal to {@link ConsoleAuthorizer} and
 * {@link ConsoleEdgeScope}. {@link #role()} and {@link #attributes()} mean whatever the identity provider and
 * those two agree on; with no console plugin there is one principal, the configured user, permitted everything.
 *
 * Immutable and serializable: it lives in an HTTP session.
 *
 * @param name       the signed-in user's name, never null
 * @param role       the provider's role name, or null when it has none
 * @param attributes anything else the provider's authorizer or edge scope needs - opaque to the core, never null
 */
public record ConsolePrincipal(String name, String role, Map<String, String> attributes) implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The request attribute every authenticated request carries. */
	public static final String REQUEST_ATTRIBUTE = "fc.principal";

	/** The session attribute a signed-in browser's principal is kept under. */
	public static final String SESSION_ATTRIBUTE = "fc.principal";

	/**
	 * The principal of a console with authentication OFF - every request is served without a check, which is what
	 * that state has always meant. Never stored in a session.
	 */
	private static final ConsolePrincipal UNAUTHENTICATED = new ConsolePrincipal("anonymous", null, Map.of());

	public ConsolePrincipal {
		Objects.requireNonNull(name, "name");
		attributes = null == attributes ? Map.of() : Map.copyOf(attributes);
	}

	/** One user with no role - the single-user sign-in's principal. */
	public static ConsolePrincipal named(String name) {
		return new ConsolePrincipal(name, null, Map.of());
	}

	/** The principal of every request to a console with authentication off. */
	public static ConsolePrincipal unauthenticated() {
		return UNAUTHENTICATED;
	}

	public boolean isUnauthenticated() {
		return this == UNAUTHENTICATED || UNAUTHENTICATED.equals(this);
	}
}
