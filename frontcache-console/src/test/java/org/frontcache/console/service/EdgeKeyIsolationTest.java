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
package org.frontcache.console.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.frontcache.console.auth.ConsolePermissions;
import org.frontcache.console.auth.ConsolePermit;
import org.frontcache.console.auth.ConsolePrincipal;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.web.DashboardStreamProxyServlet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.sun.net.httpserver.HttpServer;

/**
 * <b>The key-isolation test</b> of a console with {@code edges} (2.13.0): edges with their own API key beside the
 * {@code urls} called with the console-wide one. Both Spring contexts of a Community console, two stub nodes - one
 * under {@code urls}, one under {@code edges} with another key - and every handler of the dispatcher, enumerated
 * from its mappings, never from a list, plus the realtime stream proxy, called with each edge in every edge
 * parameter, and with an edge the console does not have.
 *
 * <b>Each node checks the key of every request it receives: only ever its own.</b> A handler that reached an edge
 * through the console-wide key instead of its scope's {@link EdgeScope.Edge} would send the {@code urls} fleet's key
 * to an {@code edges} host - a leak no screen shows, since that node merely refuses it.
 *
 * Every request parameter a handler declares or reads must be classified here as an edge or not: a handler with a
 * parameter this test cannot classify fails it, rather than being called with nothing in it.
 */
public class EdgeKeyIsolationTest {

	static final String URLS_KEY = "key-of-the-urls-fleet";
	static final String EDGES_KEY = "key-of-the-edges-entry";

	/** Parameters that name an edge - each is given the edge under test. */
	static final Set<String> EDGE_PARAMETERS = Set.of("edge", "edges", "origin", "sourceEdge", "targetEdge",
			"edgeURL", "url", "source", "target", "baseHashEdge");

	/** Parameters that name no edge, with the value that gets a handler past its own checks to its edge lookup. */
	static final Map<String, String> NOT_EDGE_PARAMETERS = new LinkedHashMap<String, String>();
	static {
		NOT_EDGE_PARAMETERS.put("file", "fallbacks");
		NOT_EDGE_PARAMETERS.put("content", "x");
		NOT_EDGE_PARAMETERS.put("baseHash", "x");
		NOT_EDGE_PARAMETERS.put("scope", "one");
		NOT_EDGE_PARAMETERS.put("key", "/x");
		NOT_EDGE_PARAMETERS.put("filter", "/x");
		NOT_EDGE_PARAMETERS.put("name", "x");
		NOT_EDGE_PARAMETERS.put("grep", "x");
		NOT_EDGE_PARAMETERS.put("plugin", "alerts");
	}

	/** What Spring reads to serve any handler: content negotiation's {@code view} (WebMVCConfig). */
	static final Set<String> FRAMEWORK_PARAMETERS = Set.of("view");

	@ClassRule
	public static TemporaryFolder folder = new TemporaryFolder();

	private static Stub fleet;
	private static Stub origin;
	private static Stub nowhere;

	private static XmlWebApplicationContext root;
	private static XmlWebApplicationContext web;
	private static MockServletContext servletContext;
	private static DispatcherServlet dispatcher;
	private static DashboardStreamProxyServlet streams;
	private static String previousConfig;

	private static final ConsolePrincipal ADMIN = ConsolePrincipal.named("admin");

	/** A node that answers 404 to everything and records the {@code Authorization} of every request. */
	static final class Stub {

		final HttpServer server;
		final List<String> received = Collections.synchronizedList(new ArrayList<String>());

		Stub() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " | "
						+ exchange.getRequestHeaders().getFirst("Authorization"));
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
			});
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
		}

		/** Every request it received that did not carry {@code Bearer <key>}. */
		List<String> notCarrying(String key) {
			List<String> wrong = new ArrayList<String>();
			synchronized (received) {
				for (String request : received)
					if (!request.endsWith(" | Bearer " + key))
						wrong.add(request);
			}
			return wrong;
		}
	}

	@BeforeClass
	public static void startConsole() throws Exception {
		assumeTrue("FRONTCACHE_CONFIG is set in this environment and would win", null == System.getenv("FRONTCACHE_CONFIG"));

		fleet = new Stub();
		origin = new Stub();
		nowhere = new Stub();

		File conf = folder.newFile("frontcache-console.conf");
		Files.writeString(conf.toPath(), """
				frontcache.console {
				  urls = ["%s"]
				  apiKey = "%s"
				  edges = [ { url = "%s", apiKey = "%s", label = origin } ]
				  user = admin
				  password = pw
				  config-editing = true
				  edge-editing = false
				}
				""".formatted(fleet.url(), URLS_KEY, origin.url(), EDGES_KEY), StandardCharsets.UTF_8);

		previousConfig = System.getProperty("org.frontcache.console.config");
		System.setProperty("org.frontcache.console.config", conf.getAbsolutePath());

		servletContext = new MockServletContext();
		root = new XmlWebApplicationContext();
		root.setServletContext(servletContext);
		root.setConfigLocation("classpath:/META-INF/spring-core-config.xml");
		root.refresh();
		servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, root);

		web = new XmlWebApplicationContext();
		web.setParent(root);
		web.setServletContext(servletContext);
		web.setConfigLocation("classpath:/META-INF/spring-mvc-config.xml");
		web.refresh();

		dispatcher = new DispatcherServlet(web);
		dispatcher.init(new MockServletConfig(servletContext, "console-dispatcher"));

		streams = new DashboardStreamProxyServlet();
		streams.init(new MockServletConfig(servletContext, "dashboard-stream"));
	}

	@AfterClass
	public static void stopConsole() {
		if (null != streams)
			streams.destroy();
		if (null != dispatcher)
			dispatcher.destroy();
		if (null != web)
			web.close();
		if (null != root)
			root.close();
		for (Stub stub : new Stub[] { fleet, origin, nowhere })
			if (null != stub)
				stub.server.stop(0);
		if (null == previousConfig)
			System.clearProperty("org.frontcache.console.config");
		else
			System.setProperty("org.frontcache.console.config", previousConfig);
	}

	// ---- one call -------------------------------------------------------------------------------------------

	/** A request that remembers every parameter name it was asked for. */
	static final class RecordingRequest extends MockHttpServletRequest {

		final Set<String> read = new TreeSet<String>();

		RecordingRequest(ServletContext context, String method, String uri) {
			super(context, method, uri);
		}

		@Override
		public String getParameter(String name) {
			read.add(name);
			return super.getParameter(name);
		}

		@Override
		public String[] getParameterValues(String name) {
			read.add(name);
			return super.getParameterValues(name);
		}
	}

	/** Calls {@code mapping} as the admin, every edge parameter naming {@code edge}; the parameters it read. */
	private static Set<String> call(Mapping mapping, String edge) throws IOException {

		RecordingRequest request = new RecordingRequest(servletContext, mapping.method(), mapping.path());
		request.setServletPath(mapping.path());
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ADMIN);
		request.setSession(SESSION);
		for (String name : EDGE_PARAMETERS)
			request.setParameter(name, edge);
		NOT_EDGE_PARAMETERS.forEach(request::setParameter);
		mapping.params().forEach(request::setParameter);
		request.setContent(new byte[0]);
		request.addHeader("Accept", mapping.accept());

		try {
			dispatcher.service(request, new MockHttpServletResponse());
		} catch (ServletException | RuntimeException e) {
			// a refusal may be an exception; what this test judges is what the nodes received
		}
		return request.read;
	}

	private static final HttpSession SESSION = new MockHttpSession();

	// ---- the handlers ---------------------------------------------------------------------------------------

	/** One handler mapping, as a request: method, path and the parameters its mapping requires. */
	record Mapping(HandlerMethod handler, String method, String path, Map<String, String> params, String accept) {

		@Override
		public String toString() {
			return method + " " + path + (params.isEmpty() ? "" : " " + params.keySet()) + " -> " + handler.getShortLogMessage();
		}
	}

	private static List<Mapping> mappings() {
		RequestMappingHandlerMapping mapping = web.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		List<Mapping> mappings = new ArrayList<Mapping>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet())
		{
			ConsolePermit permit = entry.getValue().getMethodAnnotation(ConsolePermit.class);
			if (null != permit && ConsolePermissions.ANONYMOUS.equals(permit.value()))
				continue; // the login page: no principal, no edge

			Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
			String method = methods.isEmpty() ? "GET" : methods.iterator().next().name();

			Map<String, String> params = new LinkedHashMap<String, String>();
			entry.getKey().getParamsCondition().getExpressions().forEach(e -> {
				if (!e.isNegated())
					params.put(e.getName(), null == e.getValue() ? "x" : e.getValue());
			});

			Set<org.springframework.http.MediaType> produces = entry.getKey().getProducesCondition().getProducibleMediaTypes();
			String accept = produces.isEmpty() ? "text/html" : produces.iterator().next().toString();

			for (String path : entry.getKey().getPatternValues())
				mappings.add(new Mapping(entry.getValue(), method, path, params, accept));
		}
		return mappings;
	}

	/** The parameter names a handler declares: its {@code @RequestParam}s and the properties of its form objects. */
	private static Set<String> declared(HandlerMethod handler) {
		Set<String> names = new TreeSet<String>();
		for (MethodParameter parameter : handler.getMethodParameters())
		{
			Class<?> type = parameter.getParameterType();
			RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
			if (null != param)
				names.add(!param.value().isEmpty() ? param.value() : !param.name().isEmpty() ? param.name()
						: parameter.getParameterName());
			else if (infrastructure(type))
				continue;
			else if (BeanUtils.isSimpleProperty(type))
				names.add(parameter.getParameterName());
			else
			{
				names.add(org.springframework.core.Conventions.getVariableNameForParameter(parameter));
				for (PropertyDescriptor property : BeanUtils.getPropertyDescriptors(type))
					if (null != property.getWriteMethod())
						names.add(property.getName());
			}
		}
		return names;
	}

	private static boolean infrastructure(Class<?> type) {
		return HttpServletRequest.class.isAssignableFrom(type) || HttpServletResponse.class.isAssignableFrom(type)
				|| HttpSession.class.isAssignableFrom(type) || Model.class.isAssignableFrom(type)
				|| Map.class.isAssignableFrom(type) || BindingResult.class.isAssignableFrom(type)
				|| EdgeScope.class == type || ConsolePrincipal.class == type
				|| type.getName().startsWith("org.springframework.") || type.getName().startsWith("java.io.");
	}

	private static boolean classified(String name, Mapping mapping, Set<String> allMappingParams) {
		return EDGE_PARAMETERS.contains(name) || NOT_EDGE_PARAMETERS.containsKey(name)
				|| FRAMEWORK_PARAMETERS.contains(name) || mapping.params().containsKey(name)
				|| allMappingParams.contains(name) || formAttributes(mapping.handler()).contains(name);
	}

	/** The model attribute names of a handler's form objects - the binder reads a parameter of that name. */
	private static Set<String> formAttributes(HandlerMethod handler) {
		Set<String> names = new TreeSet<String>();
		for (MethodParameter parameter : handler.getMethodParameters())
		{
			Class<?> type = parameter.getParameterType();
			if (null == parameter.getParameterAnnotation(RequestParam.class) && !infrastructure(type)
					&& !BeanUtils.isSimpleProperty(type))
				names.add(org.springframework.core.Conventions.getVariableNameForParameter(parameter));
		}
		return names;
	}

	// ---- the tests ------------------------------------------------------------------------------------------

	@Test
	public void everyHandlerCallsEachEdgeWithItsOwnKeyOnly() throws Exception {

		List<Mapping> mappings = mappings();
		Set<String> allMappingParams = new TreeSet<String>();
		mappings.forEach(m -> allMappingParams.addAll(m.params().keySet()));
		assertTrue("not a vacuous pass: " + mappings.size(), mappings.size() > 20);

		List<String> wrong = new ArrayList<String>();
		int fleetCalls = 0;
		int originCalls = 0;
		for (Mapping mapping : mappings)
		{
			for (String name : declared(mapping.handler()))
				if (!classified(name, mapping, allMappingParams))
					wrong.add(mapping + " declares '" + name + "', which this test cannot classify - an edge or not?");

			for (String edge : List.of(fleet.url(), origin.url(), nowhere.url()))
				for (String name : call(mapping, edge))
					if (!classified(name, mapping, allMappingParams))
						wrong.add(mapping + " reads '" + name + "', which this test cannot classify - an edge or not?");

			for (String request : fleet.notCarrying(URLS_KEY))
				wrong.add(mapping + " sent the urls edge another key: " + request);
			for (String request : origin.notCarrying(EDGES_KEY))
				wrong.add(mapping + " sent the edges entry another key: " + request);
			fleetCalls += fleet.received.size();
			originCalls += origin.received.size();
			fleet.received.clear();
			origin.received.clear();
		}

		// a run a handler started in the background (a replication) would have reached them by now
		Thread.sleep(300);
		for (String request : fleet.notCarrying(URLS_KEY))
			wrong.add("late: the urls edge was sent another key: " + request);
		for (String request : origin.notCarrying(EDGES_KEY))
			wrong.add("late: the edges entry was sent another key: " + request);
		if (!nowhere.received.isEmpty())
			wrong.add("an edge the console does not have was reached: " + nowhere.received);

		assertTrue(String.join("\n", wrong), wrong.isEmpty());
		// not a vacuous pass: the handlers do reach both nodes, the edges entry as often as the urls edge
		assertTrue("the urls edge was reached " + fleetCalls + " times", fleetCalls > 20);
		assertEquals("each edge is reached by the same handlers", fleetCalls, originCalls);
	}

	/** The fan-out: one poll reaches both nodes, each with its own key. */
	@Test
	public void bothNodesAreReachedEachWithItsKey() throws Exception {
		fleet.received.clear();
		origin.received.clear();

		FrontcacheService service = root.getBean(FrontcacheService.class);
		service.getClusterStatusList(new ConfiguredEdgeScope(service).everyEdge());

		assertTrue("the urls edge is polled: " + fleet.received, !fleet.received.isEmpty());
		assertTrue("the edges entry is polled: " + origin.received, !origin.received.isEmpty());
		assertEquals(List.of(), fleet.notCarrying(URLS_KEY));
		assertEquals(List.of(), origin.notCarrying(EDGES_KEY));
	}

	@Test
	public void theStreamProxySendsEachEdgeItsOwnKey() throws Exception {
		fleet.received.clear();
		origin.received.clear();
		nowhere.received.clear();

		for (Stub stub : List.of(fleet, origin))
		{
			MockHttpServletRequest request = streamRequest(stub.url() + "fc-dashboard.stream");
			streams.service(request, new MockHttpServletResponse());
			assertTrue("relayed: " + stub.url(), request.isAsyncStarted());
		}
		MockHttpServletResponse unknown = new MockHttpServletResponse();
		streams.service(streamRequest(nowhere.url() + "fc-dashboard.stream"), unknown);
		assertEquals(403, unknown.getStatus());

		long deadline = System.currentTimeMillis() + 5000;
		while ((fleet.received.isEmpty() || origin.received.isEmpty()) && System.currentTimeMillis() < deadline)
			Thread.sleep(50);

		assertTrue("the urls edge's stream was opened", !fleet.received.isEmpty());
		assertTrue("the edges entry's stream was opened", !origin.received.isEmpty());
		assertEquals(List.of(), fleet.notCarrying(URLS_KEY));
		assertEquals(List.of(), origin.notCarrying(EDGES_KEY));
		assertEquals(List.of(), nowhere.received);
	}

	private static MockHttpServletRequest streamRequest(String originParameter) {
		MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", "/fc-dashboard-proxy.stream");
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ADMIN);
		request.setSession(SESSION);
		request.setParameter("origin", originParameter);
		request.setAsyncSupported(true);
		return request;
	}
}
