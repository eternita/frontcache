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

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One thing that happened in the console, as the core reports it to {@link ConsoleAuditor} - as of 2.12.0. Built
 * from the request on the request's thread, so an auditor never needs the request itself.
 *
 * <b>It carries no secret</b>: the principal (a name, a role, the identity provider's attributes - which hold no
 * credential), the scope's id (never its keys), the peer address and the raw {@code X-Forwarded-For} (for the
 * auditor's own trusted-proxy rule - the core trusts no proxy), the user agent, the method and path (never the query
 * string), and what the handler named ({@link ConsoleAudit#parameters()}, {@link ConsoleAuditor#note}).
 *
 * @param at            when
 * @param type          {@code login.success}, {@code permission.denied}, {@code cache.invalidate}, ...
 * @param outcome       {@link ConsoleAuditor#OK} and its siblings, or null when the type says it all
 * @param principal     who, or null (a failed sign-in, an anonymous request)
 * @param scopeId       the request's {@link EdgeScope#id()}, or null when none was resolved
 * @param channel       how the principal is signed in: {@code form}, {@code sso}, {@code basic}, or {@code session} for
 *                      a request of a signed-in session; null when not signed in
 * @param remoteAddress the TCP peer
 * @param forwardedFor  the request's {@code X-Forwarded-For}, raw, or null
 * @param userAgent     the request's {@code User-Agent}, shortened, or null
 * @param request       "{@code POST /cache-invalidation}" - never the query string
 * @param status        the response status, or 0 when the event is not a response's
 * @param permission    the permission checked or refused, or null
 * @param details       what was acted on, never null
 * @param edges         per edge, what it answered - a fleet-wide action is one event with N outcomes; never null
 */
public record ConsoleAuditEvent(Instant at, String type, String outcome, ConsolePrincipal principal, String scopeId,
		String channel, String remoteAddress, String forwardedFor, String userAgent, String request, int status,
		String permission, Map<String, Object> details, Map<String, String> edges) {

	/** The request attribute {@code ConsoleAuthFilter} sets: how this request's principal is signed in. */
	public static final String CHANNEL_ATTRIBUTE = "fc.audit.channel";

	public static final String CHANNEL_FORM = "form";
	public static final String CHANNEL_SSO = "sso";
	public static final String CHANNEL_BASIC = "basic";
	public static final String CHANNEL_SESSION = "session";

	/** The longest string a request contributes to an event - a header or a parameter is the caller's to make long. */
	public static final int MAX_VALUE_LENGTH = 512;

	public ConsoleAuditEvent {
		Objects.requireNonNull(at, "at");
		Objects.requireNonNull(type, "type");
		// in the order they were added, which is the order an operator reads them in
		details = null == details ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
		edges = null == edges ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<String, String>(edges));
	}

	/**
	 * An event of this request: the principal it was served as, its scope (if one was resolved), the peer, the user
	 * agent, the path, and whatever the handler noted ({@link ConsoleAuditor#note}, its outcomes).
	 */
	public static Builder of(HttpServletRequest request, String type) {

		Builder builder = about(request, type);
		if (null == request)
			return builder;

		if (request.getAttribute(ConsoleAuditor.NOTES_ATTRIBUTE) instanceof Map<?, ?> notes)
			notes.forEach((key, value) -> builder.details.put(String.valueOf(key), value));
		if (request.getAttribute(ConsoleAuditor.EDGES_ATTRIBUTE) instanceof Map<?, ?> edges)
			edges.forEach((edge, outcome) -> builder.edges.put(String.valueOf(edge), String.valueOf(outcome)));
		if (request.getAttribute(ConsoleAuditor.OUTCOME_ATTRIBUTE) instanceof String outcome)
			builder.outcome = outcome;

		return builder;
	}

	/** Like {@link #of}, without what the handler noted - an event about the request, not the handler's action. */
	public static Builder about(HttpServletRequest request, String type) {

		Builder builder = new Builder(type);
		if (null == request)
			return builder;

		builder.principal = ConsoleSecurity.principal(request);
		if (null != builder.principal && builder.principal.isUnauthenticated())
			builder.principal = null;
		if (request.getAttribute(ConsoleSecurity.SCOPE_REQUEST_ATTRIBUTE) instanceof EdgeScope scope)
			builder.scopeId = scope.id();
		if (request.getAttribute(CHANNEL_ATTRIBUTE) instanceof String channel)
			builder.channel = channel;
		builder.remoteAddress = request.getRemoteAddr();
		builder.forwardedFor = shorten(request.getHeader("X-Forwarded-For"));
		builder.userAgent = shorten(request.getHeader("User-Agent"));
		builder.request = request.getMethod() + " " + shorten(path(request));

		return builder;
	}

	/** An event with no request - a plugin's own (a reload done by a background poll). */
	public static Builder of(String type) {
		return new Builder(type);
	}

	/** A caller-made string cut to {@link #MAX_VALUE_LENGTH}; null stays null. */
	public static String shorten(String value) {
		if (null == value)
			return null;
		return value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) + "..." : value;
	}

	private static String path(HttpServletRequest request) {
		String servletPath = null == request.getServletPath() ? "" : request.getServletPath();
		String pathInfo = null == request.getPathInfo() ? "" : request.getPathInfo();
		String path = servletPath + pathInfo;
		if (path.isEmpty())
		{
			String uri = null == request.getRequestURI() ? "" : request.getRequestURI();
			String contextPath = null == request.getContextPath() ? "" : request.getContextPath();
			path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
		}
		int semicolon = path.indexOf(';');
		return semicolon > -1 ? path.substring(0, semicolon) : path;
	}

	/** Builds an event; every setter returns the builder. */
	public static final class Builder {

		private final String type;
		private String outcome;
		private ConsolePrincipal principal;
		private String scopeId;
		private String channel;
		private String remoteAddress;
		private String forwardedFor;
		private String userAgent;
		private String request;
		private int status;
		private String permission;
		private final Map<String, Object> details = new LinkedHashMap<String, Object>();
		private final Map<String, String> edges = new LinkedHashMap<String, String>();

		private Builder(String type) {
			this.type = Objects.requireNonNull(type, "type");
		}

		public Builder outcome(String outcome) {
			this.outcome = outcome;
			return this;
		}

		/** Sets the outcome only when the handler did not say. */
		public Builder outcomeUnlessSet(String outcome) {
			if (null == this.outcome)
				this.outcome = outcome;
			return this;
		}

		public Builder principal(ConsolePrincipal principal) {
			this.principal = null != principal && principal.isUnauthenticated() ? null : principal;
			return this;
		}

		public Builder channel(String channel) {
			this.channel = channel;
			return this;
		}

		public Builder status(int status) {
			this.status = status;
			return this;
		}

		public Builder permission(String permission) {
			this.permission = permission;
			return this;
		}

		/** A detail - see {@link ConsoleAuditor#note}. A null value is left out. */
		public Builder detail(String key, Object value) {
			if (null != key && null != value)
				details.put(key, value instanceof String s ? shorten(s) : value);
			return this;
		}

		public Builder edge(String edgeURL, String outcome) {
			if (null != edgeURL && null != outcome)
				edges.put(edgeURL, outcome);
			return this;
		}

		public String outcome() {
			return outcome;
		}

		public Map<String, String> edges() {
			return edges;
		}

		public ConsoleAuditEvent build() {
			return new ConsoleAuditEvent(Instant.now(), type, outcome, principal, scopeId, channel, remoteAddress,
					forwardedFor, userAgent, request, status, permission, nonNull(details), edges);
		}

		/** Map.copyOf refuses null values - a note may not be null, but a defensive copy costs nothing. */
		private static Map<String, Object> nonNull(Map<String, Object> details) {
			Map<String, Object> copy = new LinkedHashMap<String, Object>();
			details.forEach((key, value) -> {
				if (null != key && null != value)
					copy.put(key, value);
			});
			return copy;
		}
	}
}
