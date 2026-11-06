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

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where the console reports what was done in it and by whom, as of 2.12.0 - the fifth security SPI beside
 * {@link ConsoleIdentityProvider}, {@link ConsoleAuthorizer}, {@link ConsoleEdgeScope} and {@link ConsoleSsoProvider}.
 * A console plugin provides one as a bean of its {@code rootComponents()} (an audit log); without one, nothing is
 * recorded. Two are refused at startup ({@link ConsoleSecurity}).
 *
 * The core reports, as {@link ConsoleAuditEvent}s:
 * <ul>
 * <li>{@code login.success} / {@code login.failure} (form, SSO; Basic failures only - a script's Basic success is
 *     every request, and each of its actions says {@code basic}), {@code logout}, and {@code session.ended} when the
 *     identity provider ended a session on resume;</li>
 * <li>{@code permission.denied} - a handler (or the stream proxy) refused for want of a permission;</li>
 * <li>{@code edge.denied} - a request named an edge outside its scope: on a multi-tenant console, another tenant's
 *     edge, told apart from no edge at all by nothing but this record;</li>
 * <li>every <b>state-changing handler</b> - every non-GET handler that is not the login controller's, the core's and
 *     every plugin's, enumerated by the dispatcher rather than listed - once it has completed, with its type and the
 *     request parameters it names in {@link ConsoleAudit}, the details it added with {@link #note}, and its outcome.</li>
 * </ul>
 *
 * <b>Nothing the request carries reaches an event unless a handler names it.</b> No form field, header, cookie or
 * body is copied wholesale: a password, an API key, a session id, an {@code Authorization} header, an OIDC
 * {@code code} or a config file's content can only be recorded by a handler naming it, and none does.
 *
 * <b>Recording never blocks or fails the action</b>: {@link ConsoleSecurity#audit} catches what an auditor throws.
 */
public interface ConsoleAuditor {

	/**
	 * Records one event. Called on the request's thread, after the action - an implementation must be quick, must not
	 * throw (it is caught and logged if it does), and must not keep the request.
	 */
	void record(ConsoleAuditEvent event);

	// ---- what a handler adds to its own event ---------------------------------------------------------------------

	/** The request attribute a handler's details are collected in until its event is recorded. */
	String NOTES_ATTRIBUTE = "fc.audit.notes";

	/** The request attribute a handler's outcome is kept in. */
	String OUTCOME_ATTRIBUTE = "fc.audit.outcome";

	/** The request attribute the per-edge outcomes are collected in. */
	String EDGES_ATTRIBUTE = "fc.audit.edges";

	/** What an action came to, when a handler says so. Without one, the response status decides. */
	String OK = "ok";
	String REFUSED = "refused";
	String FAILED = "failed";
	/** Some edges did it and some did not - the per-edge outcomes say which. */
	String PARTIAL = "partial";

	/**
	 * A detail of this request's event - what was acted on, a hash, a size, a result. <b>Never a secret, a credential
	 * or content</b>: the caller decides what is recorded, and a config file is recorded by its hash and size.
	 *
	 * @param value a String, Number, Boolean, or a List / Map of those
	 */
	static void note(HttpServletRequest request, String key, Object value) {
		if (null == request || null == key || null == value)
			return;
		notes(request).put(key, value);
	}

	/** What this request's action came to: {@link #OK}, {@link #REFUSED}, {@link #FAILED} or {@link #PARTIAL}. */
	static void outcome(HttpServletRequest request, String outcome) {
		if (null != request && null != outcome)
			request.setAttribute(OUTCOME_ATTRIBUTE, outcome);
	}

	/** What one edge of a fleet-wide action answered - one event with N outcomes, never N events. */
	static void edgeOutcome(HttpServletRequest request, String edgeURL, String outcome) {
		if (null == request || null == edgeURL || null == outcome)
			return;
		@SuppressWarnings("unchecked")
		Map<String, String> edges = (Map<String, String>) request.getAttribute(EDGES_ATTRIBUTE);
		if (null == edges)
		{
			edges = new LinkedHashMap<String, String>();
			request.setAttribute(EDGES_ATTRIBUTE, edges);
		}
		edges.put(edgeURL, outcome);
	}

	private static Map<String, Object> notes(HttpServletRequest request) {
		@SuppressWarnings("unchecked")
		Map<String, Object> notes = (Map<String, Object>) request.getAttribute(NOTES_ATTRIBUTE);
		if (null == notes)
		{
			notes = new LinkedHashMap<String, Object>();
			request.setAttribute(NOTES_ATTRIBUTE, notes);
		}
		return notes;
	}
}
