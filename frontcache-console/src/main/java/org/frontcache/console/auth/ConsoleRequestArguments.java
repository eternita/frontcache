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

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a handler method - the core's or a plugin's - declare an {@link EdgeScope} or {@link ConsolePrincipal}
 * parameter and get the request's, as of 2.12.0. This is how a controller reaches edges: there is no console-wide
 * edge list to reach for instead.
 */
public class ConsoleRequestArguments implements HandlerMethodArgumentResolver {

	private final ConsoleSecurity security;

	public ConsoleRequestArguments(ConsoleSecurity security) {
		this.security = security;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		return EdgeScope.class == type || ConsolePrincipal.class == type;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		if (EdgeScope.class == parameter.getParameterType())
			return security.scope(request);

		return ConsoleSecurity.principal(request);
	}
}
