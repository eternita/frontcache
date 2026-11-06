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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Checks a handler's {@link ConsolePermit} before it runs, as of 2.12.0 - for the core's controllers and every
 * plugin's alike, since both are dispatched by the one {@code console-dispatcher}.
 *
 * <ul>
 * <li>A handler with a {@link ConsolePermit} needs that permission.</li>
 * <li>A GET or HEAD handler without one needs {@link ConsolePermissions#VIEW}.</li>
 * <li><b>Any other handler without one is refused</b> - fail closed: a POST added without a permission must not be
 *     open to every viewer. {@code ConsolePermitTest} catches it at build time; this catches it at run time.</li>
 * </ul>
 *
 * A refusal is {@code 403} naming the permission - never a redirect to {@code /login}, which would read as "session
 * expired".
 */
public class ConsolePermissionInterceptor implements HandlerInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(ConsolePermissionInterceptor.class);

	private final ConsoleSecurity security;

	public ConsolePermissionInterceptor(ConsoleSecurity security) {
		this.security = security;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

		if (!(handler instanceof HandlerMethod method))
			return true; // static resources: the auth filter has already decided

		// for the page's buttons - what this principal may use is offered, the rest hidden (and refused below)
		request.setAttribute(ConsoleSecurity.PERMITS_REQUEST_ATTRIBUTE, security.permitsView(request));
		ConsolePrincipal principal = ConsoleSecurity.principal(request);
		if (null != principal && null != principal.role())
			request.setAttribute(ConsoleSecurity.ROLE_REQUEST_ATTRIBUTE, principal.role());
		ScopeMenu scopeMenu = security.scopeMenu(request);
		if (null != scopeMenu)
			request.setAttribute(ConsoleSecurity.SCOPE_MENU_REQUEST_ATTRIBUTE, scopeMenu);

		String permission = requiredPermission(method, request.getMethod());
		if (null == permission)
		{
			logger.error("{} {} is handled by {} which declares no @ConsolePermit - refused", request.getMethod(),
					request.getRequestURI(), method.getShortLogMessage());
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "This action declares no permission");
			return false;
		}

		ConsolePermit permit = method.getMethodAnnotation(ConsolePermit.class);
		String edge = null == permit || permit.edgeParameter().isEmpty() ? null
				: request.getParameter(permit.edgeParameter());

		if (security.permits(request, permission, edge))
			return true;

		security.audit(ConsoleAuditEvent.about(request, "permission.denied")
				.permission(permission).detail("edge", edge).outcome(ConsoleAuditor.REFUSED)
				.status(HttpServletResponse.SC_FORBIDDEN));

		response.setHeader("Cache-Control", "no-store");
		response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden: this needs the '" + permission + "' permission");
		return false;
	}

	/**
	 * The audit's half, once the handler has run (and only when {@code preHandle} let it): one {@code edge.denied} per
	 * edge the request named outside its scope, and - for every handler that changes something, which is every one
	 * that is not a GET or HEAD and not the login controller's (that one records its own sign-ins) - one event saying
	 * what it was, with the parameters its {@link ConsoleAudit} names, the details it noted, and how it went.
	 * Enumerated by the dispatcher, never listed: a new POST is recorded the day it is added.
	 */
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {

		if (!security.auditing() || !(handler instanceof HandlerMethod method))
			return;

		Set<String> refusedEdges = ConsoleSecurity.refusedEdges(request);
		for (String edge : refusedEdges)
			security.audit(ConsoleAuditEvent.about(request, "edge.denied")
					.detail("edge", edge).outcome(ConsoleAuditor.REFUSED).status(response.getStatus()));

		String permission = requiredPermission(method, request.getMethod());
		if (!changesState(request.getMethod()) || null == permission || ConsolePermissions.ANONYMOUS.equals(permission))
			return;

		ConsoleAudit audit = method.getMethodAnnotation(ConsoleAudit.class);
		ConsolePermit permit = method.getMethodAnnotation(ConsolePermit.class);
		String type = null != audit && !audit.value().isBlank() ? audit.value() : permission;

		ConsoleAuditEvent.Builder event = ConsoleAuditEvent.of(request, type)
				.permission(permission).status(response.getStatus());

		// the permit's edge first, then the named parameters - and nothing else the request carried
		if (null != permit && !permit.edgeParameter().isEmpty())
			event.detail(permit.edgeParameter(), parameter(request, permit.edgeParameter()));
		if (null != audit)
			for (String name : audit.parameters())
				if (!NEVER_RECORDED.contains(name.toLowerCase(java.util.Locale.ROOT)))
					event.detail(name, parameter(request, name));

		event.outcomeUnlessSet(outcome(response.getStatus(), ex, !refusedEdges.isEmpty(), event.edges()));
		security.audit(event);
	}

	private static boolean changesState(String httpMethod) {
		return !"GET".equalsIgnoreCase(httpMethod) && !"HEAD".equalsIgnoreCase(httpMethod);
	}

	/** A parameter, or null - never an exception: a raw-body handler has read the stream a form parse would need. */
	private static String parameter(HttpServletRequest request, String name) {
		try {
			return request.getParameter(name);
		} catch (RuntimeException unreadable) {
			return null;
		}
	}

	/**
	 * What an action came to when its handler did not say: an exception or a 5xx failed, a 4xx or an edge outside the
	 * scope was refused, per-edge outcomes decide between ok, partial and failed, and anything else is ok.
	 */
	static String outcome(int status, Exception ex, boolean edgeRefused, Map<String, String> edges) {

		if (null != ex || status >= 500)
			return ConsoleAuditor.FAILED;
		if (status >= 400 || edgeRefused)
			return ConsoleAuditor.REFUSED;
		if (!edges.isEmpty())
		{
			long ok = edges.values().stream().filter(ConsoleAuditor.OK::equals).count();
			return ok == edges.size() ? ConsoleAuditor.OK : 0 == ok ? ConsoleAuditor.FAILED : ConsoleAuditor.PARTIAL;
		}
		return ConsoleAuditor.OK;
	}

	/**
	 * Every handler of a dispatcher that breaks the rule, as "{@code METHOD path -> handler: why}": a non-GET mapping
	 * without a {@link ConsolePermit}, or an {@link ConsolePermissions#ANONYMOUS} one outside {@code anonymousHandlers}.
	 * For tests - the core's, and a plugin's over the same dispatcher - so the rule is enumerated, never remembered.
	 *
	 * @param anonymousHandlers the classes allowed to serve anonymous requests (the login controller)
	 */
	public static List<String> violations(RequestMappingHandlerMapping mapping, Set<Class<?>> anonymousHandlers) {

		List<String> violations = new ArrayList<String>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet())
		{
			HandlerMethod method = entry.getValue();
			ConsolePermit permit = method.getMethodAnnotation(ConsolePermit.class);
			Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
			String where = methods + " " + entry.getKey().getPatternValues() + " -> " + method.getShortLogMessage();

			boolean readOnly = !methods.isEmpty() && methods.stream()
					.allMatch(m -> RequestMethod.GET == m || RequestMethod.HEAD == m);

			if (null == permit && !readOnly)
				violations.add(where + ": not GET-only and declares no @ConsolePermit");
			else if (null != permit && permit.value().isBlank())
				violations.add(where + ": an empty @ConsolePermit");
			else if (null != permit && ConsolePermissions.ANONYMOUS.equals(permit.value())
					&& !anonymousHandlers.contains(method.getBeanType()))
				violations.add(where + ": anonymous outside the login controller");
		}
		return violations;
	}

	/**
	 * Parameter names that may hold a credential or content - never recorded, whatever a {@link ConsoleAudit} names.
	 * Compared case-insensitively.
	 */
	public static final Set<String> NEVER_RECORDED = Set.of("password", "content", "apikey", "api-key", "key-hash",
			"token", "secret", "code", "authorization", "cookie", "session", "body");

	/**
	 * Every {@link ConsoleAudit} of a dispatcher that breaks the audit's rule, as "{@code handler: why}": on a GET-only
	 * handler (a read is never recorded, so a name there is a mistake), or naming a {@link #NEVER_RECORDED} parameter.
	 * For tests - the core's and a plugin's - like {@link #violations}.
	 */
	public static List<String> auditViolations(RequestMappingHandlerMapping mapping) {

		List<String> violations = new ArrayList<String>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet())
		{
			ConsoleAudit audit = entry.getValue().getMethodAnnotation(ConsoleAudit.class);
			if (null == audit)
				continue;
			Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
			if (methods.isEmpty() || methods.stream().allMatch(m -> RequestMethod.GET == m || RequestMethod.HEAD == m))
				violations.add(entry.getValue().getShortLogMessage() + ": @ConsoleAudit on a read - reads are not recorded");
			for (String parameter : audit.parameters())
				if (NEVER_RECORDED.contains(parameter.toLowerCase(java.util.Locale.ROOT)))
					violations.add(entry.getValue().getShortLogMessage() + ": records '" + parameter
							+ "', which may hold a secret or content");
		}
		return violations;
	}

	/** The permission a handler needs, or null when it declares none and is not a GET. */
	static String requiredPermission(HandlerMethod method, String httpMethod) {

		ConsolePermit permit = method.getMethodAnnotation(ConsolePermit.class);
		if (null != permit)
			return permit.value();

		if ("GET".equalsIgnoreCase(httpMethod) || "HEAD".equalsIgnoreCase(httpMethod))
			return ConsolePermissions.VIEW;

		return null;
	}
}
