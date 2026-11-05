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

		response.setHeader("Cache-Control", "no-store");
		response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden: this needs the '" + permission + "' permission");
		return false;
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
