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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.controllers.LoginController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every console handler declares what it needs - enumerated from the dispatcher's own handler mappings, never from a
 * list - and the interceptor enforces it, failing closed.
 */
public class ConsolePermitTest {

	private XmlWebApplicationContext root;
	private XmlWebApplicationContext web;

	@Before
	public void startContexts() {
		MockServletContext servletContext = new MockServletContext();

		root = new XmlWebApplicationContext();
		root.setServletContext(servletContext);
		root.setConfigLocation("classpath:/META-INF/spring-core-config.xml");
		root.refresh();

		web = new XmlWebApplicationContext();
		web.setParent(root);
		web.setServletContext(servletContext);
		web.setConfigLocation("classpath:/META-INF/spring-mvc-config.xml");
		web.refresh();
	}

	@After
	public void stopContexts() {
		if (null != web)
			web.close();
		if (null != root)
			root.close();
	}

	private RequestMappingHandlerMapping mapping() {
		return web.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
	}

	@Test
	public void everyStateChangingHandlerDeclaresAPermission() {
		List<String> violations = ConsolePermissionInterceptor.violations(mapping(), Set.of(LoginController.class));
		assertTrue("handlers breaking the permission rule:\n" + String.join("\n", violations), violations.isEmpty());
	}

	/** Not a vacuous pass: the core has POST handlers, and each names a core permission. */
	@Test
	public void theCoresPostHandlersNameCorePermissions() {
		List<String> seen = new ArrayList<String>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping().getHandlerMethods().entrySet())
		{
			ConsolePermit permit = entry.getValue().getMethodAnnotation(ConsolePermit.class);
			if (null == permit)
				continue;
			seen.add(permit.value());
			assertTrue(entry.getValue() + " names " + permit.value(),
					ConsolePermissions.CORE.contains(permit.value()) || ConsolePermissions.ANONYMOUS.equals(permit.value()));
		}
		assertTrue(seen.toString(), seen.containsAll(ConsolePermissions.CORE.stream()
				.filter(p -> !ConsolePermissions.VIEW.equals(p)).toList()));
	}

	/**
	 * The audit's half of the enumeration: {@link ConsoleAudit} only on a handler that changes something (a GET is
	 * never recorded, so a name there is a mistake), never naming a parameter that holds a secret or content, and on
	 * every core POST - so each has a type an operator can filter by. Plugins' handlers get the same check in their own
	 * module (Pro: ConsolePluginWiringTest).
	 */
	@Test
	public void auditAnnotationsNameOnlyIdentifiers() {
		List<String> violations = ConsolePermissionInterceptor.auditViolations(mapping());
		assertTrue(String.join("\n", violations), violations.isEmpty());

		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping().getHandlerMethods().entrySet())
		{
			ConsolePermit permit = entry.getValue().getMethodAnnotation(ConsolePermit.class);
			boolean post = entry.getKey().getMethodsCondition().getMethods()
					.contains(org.springframework.web.bind.annotation.RequestMethod.POST);
			if (post && null != permit && !ConsolePermissions.ANONYMOUS.equals(permit.value()))
				assertTrue(entry.getValue() + " has no @ConsoleAudit",
						null != entry.getValue().getMethodAnnotation(ConsoleAudit.class));
		}
	}

	@Test
	public void theViolationCheckCatchesAnUnmarkedPost() throws Exception {
		RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
		mapping.registerMapping(RequestMappingInfo.paths("/open").methods(
				org.springframework.web.bind.annotation.RequestMethod.POST).build(),
				new Handlers(), Handlers.class.getMethod("open"));
		mapping.registerMapping(RequestMappingInfo.paths("/sneaky").methods(
				org.springframework.web.bind.annotation.RequestMethod.POST).build(),
				new Handlers(), Handlers.class.getMethod("sneaky"));
		mapping.registerMapping(RequestMappingInfo.paths("/read").methods(
				org.springframework.web.bind.annotation.RequestMethod.GET).build(),
				new Handlers(), Handlers.class.getMethod("read"));

		List<String> violations = ConsolePermissionInterceptor.violations(mapping, Set.of(LoginController.class));

		assertEquals(violations.toString(), 2, violations.size());
		assertTrue(violations.toString(), violations.stream().anyMatch(v -> v.contains("/open") && v.contains("no @ConsolePermit")));
		assertTrue(violations.toString(), violations.stream().anyMatch(v -> v.contains("/sneaky") && v.contains("anonymous")));
	}

	// ---- the interceptor -------------------------------------------------------------------------

	public static class Handlers {
		public void open() {
		}

		@ConsolePermit(ConsolePermissions.ANONYMOUS)
		public void sneaky() {
		}

		public void read() {
		}

		@ConsolePermit(ConsolePermissions.CACHE_INVALIDATE)
		public void invalidate() {
		}
	}

	private static HandlerMethod handler(String name) throws Exception {
		return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
	}

	private static ConsolePermissionInterceptor interceptor(ConsoleAuthorizer authorizer) {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		when(service.getConsoleConfig()).thenReturn(null);
		return new ConsolePermissionInterceptor(new ConsoleSecurity(service, null, authorizer, null, null));
	}

	private static MockHttpServletRequest request(String method) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/x");
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.named("bob"));
		request.setParameter("edge", "https://n1.example.com/");
		return request;
	}

	@Test
	public void aPostWithNoPermissionIsRefusedEvenToAnAdmin() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		assertFalse(interceptor((who, p, e) -> true).preHandle(request("POST"), response, handler("open")));
		assertEquals(403, response.getStatus());
	}

	@Test
	public void aGetNeedsView() throws Exception {
		List<String> asked = new ArrayList<String>();
		assertTrue(interceptor((who, p, e) -> asked.add(p)).preHandle(request("GET"), new MockHttpServletResponse(), handler("read")));
		assertEquals(List.of(ConsolePermissions.VIEW), asked);
		assertEquals(ConsolePermissions.VIEW, ConsolePermissionInterceptor.requiredPermission(handler("read"), "HEAD"));
		assertNull(ConsolePermissionInterceptor.requiredPermission(handler("read"), "DELETE"));
	}

	/** A refusal is a 403 naming the permission - never a redirect to /login, which reads as "session expired". */
	@Test
	public void aRefusedPermissionIsA403NamingIt() throws Exception {
		List<String> edges = new ArrayList<String>();
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertFalse(interceptor((who, p, e) -> edges.add(e) && false)
				.preHandle(request("POST"), response, handler("invalidate")));

		assertEquals(403, response.getStatus());
		assertTrue(response.getErrorMessage(), response.getErrorMessage().contains(ConsolePermissions.CACHE_INVALIDATE));
		assertNull(response.getRedirectedUrl());
		assertEquals("the authorizer is told the edge", List.of("https://n1.example.com/"), edges);
	}

	/**
	 * The page's buttons: {@code fcPermits} answers what the authorizer says, console-wide (no edge), and is set on a
	 * request whatever the handler's own outcome.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void thePageIsToldWhatThePrincipalMayDo() throws Exception {
		List<String> edges = new ArrayList<String>();
		MockHttpServletRequest request = request("GET");

		assertTrue(interceptor((who, p, e) -> edges.add(String.valueOf(e)) && !ConsolePermissions.CONFIG_WRITE.equals(p))
				.preHandle(request, new MockHttpServletResponse(), handler("read")));

		Map<String, Boolean> permits = (Map<String, Boolean>) request.getAttribute(ConsoleSecurity.PERMITS_REQUEST_ATTRIBUTE);
		assertEquals(Boolean.TRUE, permits.get(ConsolePermissions.CACHE_INVALIDATE));
		assertEquals(Boolean.FALSE, permits.get(ConsolePermissions.CONFIG_WRITE));
		assertEquals(Boolean.FALSE, permits.get(""));
		assertEquals(Boolean.FALSE, permits.get(null));
		assertEquals("view for the handler (no @ConsolePermit, so no edge), then each asked once, with no edge",
				List.of("null", "null", "null"), edges);
		permits.get(ConsolePermissions.CACHE_INVALIDATE);
		assertEquals(3, edges.size());
		assertNull("the single user has no role to show", request.getAttribute(ConsoleSecurity.ROLE_REQUEST_ATTRIBUTE));

		MockHttpServletRequest withRole = request("GET");
		withRole.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, new ConsolePrincipal("bob", "operator", Map.of()));
		interceptor((who, p, e) -> true).preHandle(withRole, new MockHttpServletResponse(), handler("read"));
		assertEquals("a String the navbar's EL can read", "operator", withRole.getAttribute(ConsoleSecurity.ROLE_REQUEST_ATTRIBUTE));
	}

	@Test
	public void aGrantedPermissionPasses() throws Exception {
		assertTrue(interceptor((who, p, e) -> ConsolePermissions.CACHE_INVALIDATE.equals(p))
				.preHandle(request("POST"), new MockHttpServletResponse(), handler("invalidate")));
	}
}
