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
package org.frontcache.plugin.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.frontcache.FCConfig;
import org.frontcache.core.EvidenceCookies;
import org.frontcache.io.PluginStats;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.event.ChallengeResult;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.serve.EvidenceSigner;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.frontcache.plugin.serve.PluginRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * E2 Serve: {@code /fc-x/<id>/} answered by the plugin that owns it, and only by it - with the node deciding what
 * the plugin sees, what it may answer, and what a failing plugin's path answers (503, nothing of what it wrote).
 */
public class ServePluginTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef-test";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void setUp() {
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, SECRET);
	}

	@After
	public void tearDown() {
		PluginEndpoints.clear();
		FrontcachePlugins.install(List.of());
		FcEventBus.stopAll();
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
	}

	private static PluginCounters install(String id, PluginEndpoint endpoint) {
		PluginCounters counters = new PluginCounters();
		PluginManifest manifest = ChallengeRuleTest.manifest(id, "serve", "fc_pass", "pow");
		PluginEndpoints.install(Map.of(id, PluginEndpoints.served(id, manifest, endpoint, counters)));
		return counters;
	}

	private static ServletFakes.Response serve(ServletFakes.Request request) {
		ServletFakes.Response response = new ServletFakes.Response();
		PluginEndpoints.serve(request.context(response), t -> t.startsWith("/") && !t.startsWith("//"));
		return response;
	}

	// ---- routing ---------------------------------------------------------------------------------------------------

	@Test
	public void thePrefixIsReservedAndAPathNoPluginOwnsIsA404() {
		assertTrue(PluginEndpoints.isReserved("/fc-x/"));
		assertTrue(PluginEndpoints.isReserved("/fc-x"));
		assertTrue(PluginEndpoints.isReserved("/fc-x/anything/at/all"));
		assertFalse(PluginEndpoints.isReserved("/fc-xyz"));
		assertFalse(PluginEndpoints.isReserved("/en/fc-x/"));
		assertFalse(PluginEndpoints.isReserved(null));

		for (String uri : new String[] { "/fc-x", "/fc-x/", "/fc-x/nobody/", "/fc-x/nobody/verify?x=1" })
		{
			ServletFakes.Response response = serve(new ServletFakes.Request().uri(uri));
			assertEquals(uri, 404, response.status);
			assertEquals("404 Not Found\n", response.text());
			assertTrue(response.header("Cache-Control").startsWith("no-store"));
		}
	}

	@Test
	public void theOwnerAnswersItsPathAndTheNodeAddsNoStore() {
		AtomicReference<String> seenPath = new AtomicReference<>();
		PluginCounters counters = install("challenge", (req, res) -> {
			seenPath.set(req.path() + " " + req.parameter("p") + " " + req.queryString());
			res.contentType("text/html; charset=UTF-8");
			res.header("Content-Security-Policy", "default-src 'self'");
			res.writer().write("<p>solve me</p>");
		});

		ServletFakes.Response response = serve(new ServletFakes.Request().uri("/fc-x/challenge/?p=pow&return=%2Fsearch"));

		assertEquals(200, response.status);
		assertEquals("<p>solve me</p>", response.text());
		assertEquals("text/html; charset=UTF-8", response.header("Content-Type"));
		assertEquals("default-src 'self'", response.header("Content-Security-Policy"));
		assertTrue(response.header("Cache-Control").startsWith("no-store"));
		assertEquals("nosniff", response.header("X-Content-Type-Options"));
		assertEquals("/ pow p=pow&return=%2Fsearch", seenPath.get());
		assertEquals(1, counters.served.sum());

		serve(new ServletFakes.Request().uri("/fc-x/challenge"));
		assertEquals("/ null ", seenPath.get());
		serve(new ServletFakes.Request().uri("/fc-x/challenge/verify"));
		assertEquals("/verify null ", seenPath.get());
	}

	@Test
	public void thePluginSeesNoCredentialNoForeignCookieAndNoNodeHeader() {
		AtomicReference<PluginRequest> seen = new AtomicReference<>();
		AtomicReference<String> values = new AtomicReference<>();
		install("challenge", (req, res) -> {
			seen.set(req);
			values.set(req.header("Authorization") + " " + req.header("Cookie") + " " + req.header("x-frontcache-site-key")
					+ " " + req.cookie("fc_pass") + " " + req.cookie("JSESSIONID") + " " + req.header("accept-language")
					+ " " + req.clientAddress() + " " + req.clientType() + " " + req.scheme());
		});

		serve(new ServletFakes.Request().uri("/fc-x/challenge/").header("Authorization", "Basic c2VjcmV0")
				.header("x-frontcache-site-key", "api-key").header("Accept-Language", "de")
				.header("Cookie", "fc_pass=1; JSESSIONID=s").cookie("fc_pass", "1").cookie("JSESSIONID", "s"));

		assertEquals("null null null 1 null de 203.0.113.7 guest https", values.get());
		for (String name : seen.get().headerNames())
			assertFalse(name, CorePluginRequest.hidden(name));
		assertTrue(seen.get().headerNames().contains("Accept-Language"));
	}

	@Test
	public void aFailingPluginsPathAnswers503AndNothingItWrote() {
		PluginCounters counters = install("broken", (req, res) -> {
			res.writer().write("half a page");
			throw new IllegalStateException("broken on purpose");
		});

		ServletFakes.Response response = serve(new ServletFakes.Request().uri("/fc-x/broken/"));

		assertEquals(503, response.status);
		assertEquals("503 Service Unavailable\n", response.text());
		assertEquals(1, counters.serveFailed.sum());
		assertEquals(1, counters.errors.sum());
		assertEquals(0, counters.served.sum());
	}

	@Test
	public void theResponseIsNarrowerThanAServletResponse() {
		List<String> refused = Collections.synchronizedList(new ArrayList<>());
		AtomicReference<String> redirected = new AtomicReference<>();
		install("challenge", (req, res) -> {
			for (Runnable attempt : List.<Runnable>of(
					() -> res.header("Set-Cookie", "session=stolen"),
					() -> res.header("Cache-Control", "public, max-age=3600"),
					() -> res.header("Location", "https://evil.example.com/"),
					() -> res.header("Content-Type", "text/html\r\nSet-Cookie: x=y"),
					() -> res.cookie("JSESSIONID", "x", Duration.ofHours(1)),
					() -> res.cookie("fc_pass", "a value", Duration.ofHours(1)),
					() -> res.redirect(200, "/")))
				try {
					attempt.run();
				} catch (IllegalArgumentException e) {
					refused.add(e.getMessage());
				}
			redirected.set(res.redirect(303, req.parameter("return")));
			res.cookie("fc_pass", "123.abc", Duration.ofHours(1));
		});

		ServletFakes.Response evil = serve(new ServletFakes.Request().uri("/fc-x/challenge/?return=https%3A%2F%2Fevil.example.com%2F"));
		assertEquals(7, refused.size());
		assertEquals("a return address the node does not publish goes home", "/", redirected.get());
		assertEquals(303, evil.status);
		assertEquals("/", evil.header("Location"));
		assertEquals("fc_pass=123.abc; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax; Secure", evil.header("Set-Cookie"));
		assertTrue(evil.header("Cache-Control").startsWith("no-store"));

		ServletFakes.Response home = serve(new ServletFakes.Request().uri("/fc-x/challenge/?return=%2Fsearch%3Fq%3Dx"));
		assertEquals("/search?q=x", home.header("Location"));
	}

	@Test
	public void aBodyOverTheLimitIs413AndAFormIsParsed() {
		AtomicReference<Map<String, List<String>>> form = new AtomicReference<>();
		PluginCounters counters = install("challenge", (req, res) -> form.set(req.formParameters()));

		ServletFakes.Response ok = serve(new ServletFakes.Request().method("POST").uri("/fc-x/challenge/verify")
				.body("application/x-www-form-urlencoded", "solution=42&return=%2Fsearch&solution=43", true));
		assertEquals(200, ok.status);
		assertEquals(List.of("42", "43"), form.get().get("solution"));
		assertEquals(List.of("/search"), form.get().get("return"));

		String big = "x=" + "a".repeat((int) PluginEndpoints.DEFAULT_MAX_BODY + 10);
		assertEquals("declared too large: refused before the plugin", 413, serve(new ServletFakes.Request().method("POST")
				.uri("/fc-x/challenge/verify").body("application/x-www-form-urlencoded", big, true)).status);
		assertEquals("read too large (chunked)", 413, serve(new ServletFakes.Request().method("POST")
				.uri("/fc-x/challenge/verify").body("application/x-www-form-urlencoded", big, false)).status);
		assertEquals(2, counters.serveRefused.sum());
		assertEquals(0, counters.serveFailed.sum());
	}

	@Test
	public void overItsCapAPluginsPathAnswers503WithoutReachingIt() throws Exception {
		FCConfig.setProperty("front-cache.plugins.slow.max-concurrent", "1");
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		PluginCounters counters;
		try {
			counters = install("slow", (req, res) -> {
				inside.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		} finally {
			FCConfig.setProperty("front-cache.plugins.slow.max-concurrent", "32");
		}

		Thread first = new Thread(() -> serve(new ServletFakes.Request().uri("/fc-x/slow/")));
		first.start();
		assertTrue(inside.await(10, TimeUnit.SECONDS));

		ServletFakes.Response busy = serve(new ServletFakes.Request().uri("/fc-x/slow/"));
		release.countDown();
		first.join(10_000);

		assertEquals(503, busy.status);
		assertEquals("5", busy.header("Retry-After"));
		assertEquals(1, counters.serveRefused.sum());
		assertEquals(1, counters.served.sum());
	}

	// ---- the evidence signer ---------------------------------------------------------------------------------------

	@Test
	public void theSignerSignsOnlyWhatTheManifestNamesOnlyOnItsOwnRequest() throws Exception {
		List<FcEvent> events = Collections.synchronizedList(new ArrayList<>());
		PluginCounters sink = new PluginCounters();
		FcEventBus.subscribe("sink", FcEventBusTest.listener(Set.of(ChallengeResult.class), events::addAll), 100, sink);

		PluginManifest manifest = ChallengeRuleTest.manifest("challenge", "serve", "fc_pass", "pow");
		EvidenceSigner signer = new CorePluginContext(manifest, null).evidenceSigner();
		assertNotNull(signer);
		AtomicReference<String> value = new AtomicReference<>();
		List<String> refused = new ArrayList<>();
		install("challenge", (req, res) -> {
			value.set(signer.sign(req, "pow", "fc_pass", Duration.ofHours(1), EvidenceSigner.Binding.CLIENT_IP));
			res.cookie("fc_pass", value.get(), Duration.ofHours(1));
			signer.failed(req, "pow", false);
			signer.failed(req, "pow", true);
			for (Runnable attempt : List.<Runnable>of(
					() -> signer.sign(req, "pow", "fc_admin", Duration.ofHours(1)),
					() -> signer.sign(req, "turnstile", "fc_pass", Duration.ofHours(1)),
					() -> signer.sign(req, "pow", "fc_pass", Duration.ofDays(31)),
					() -> signer.sign(req, "pow", "fc_pass", Duration.ZERO),
					() -> signer.sign(new ForeignRequest(), "pow", "fc_pass", Duration.ofHours(1))))
				try {
					attempt.run();
				} catch (IllegalArgumentException e) {
					refused.add(e.getMessage());
				}
		});

		serve(new ServletFakes.Request().uri("/fc-x/challenge/verify").from("203.0.113.7"));

		assertEquals(5, refused.size());
		assertTrue("the core verifies what it signed", EvidenceCookies.holds(
				new ServletFakes.Request().from("203.0.113.7").cookie("fc_pass", value.get()).context(), "fc_pass"));
		assertFalse("bound to the address the node resolved", EvidenceCookies.holds(
				new ServletFakes.Request().from("198.51.100.1").cookie("fc_pass", value.get()).context(), "fc_pass"));

		FcEventBusTest.waitFor(() -> 3 == sink.delivered.sum());
		List<String> outcomes = new ArrayList<>();
		for (FcEvent event : events)
			outcomes.add(((ChallengeResult) event).outcome() + "/" + ((ChallengeResult) event).provider() + "/"
					+ ((ChallengeResult) event).plugin() + "/" + ((ChallengeResult) event).clientKey());
		assertEquals(List.of("pass/pow/challenge/203.0.113.7", "fail/pow/challenge/203.0.113.7",
				"error/pow/challenge/203.0.113.7"), outcomes);
	}

	@Test
	public void thereIsNoSignerWithoutASecretACookieOrServe() {
		assertNull(new CorePluginContext(ChallengeRuleTest.manifest("aa", "serve", null, "pow"), null).evidenceSigner());
		assertNull(new CorePluginContext(ChallengeRuleTest.manifest("bb", "manage", null, null), null).evidenceSigner());
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
		assertNull(new CorePluginContext(ChallengeRuleTest.manifest("cc", "serve", "fc_pass", "pow"), null).evidenceSigner());
	}

	@Test
	public void aManifestNamesCookiesAndChallengesOnlyWithServe() {
		try {
			ChallengeRuleTest.manifest("xx", "observe", "fc_pass", "pow");
			fail("cookies and challenges need serve");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("'cookies' needs kind 'serve'"));
			assertTrue(expected.getMessage(), expected.getMessage().contains("'challenges' needs kind 'serve'"));
		}
		try {
			ChallengeRuleTest.manifest("xx", "serve", "bad cookie;", "Pow");
			fail("names are checked");
		} catch (IllegalArgumentException expected) {
		}
		PluginManifest ok = ChallengeRuleTest.manifest("xx", "serve", "fc_pass, fc_wait", "pow, waiting-room");
		assertEquals(Set.of("fc_pass", "fc_wait"), ok.cookies());
		assertEquals(Set.of("pow", "waiting-room"), ok.challenges());
	}

	// ---- through the registry --------------------------------------------------------------------------------------

	public static class Challenger implements FrontcachePlugin {
		static volatile PluginContext context;

		@Override
		public void start(PluginContext pluginContext) {
			context = pluginContext;
		}

		@Override
		public PluginEndpoint endpoint() {
			return (req, res) -> res.writer().write("hello from " + context.manifest().id());
		}
	}

	public static class UndeclaredServer implements FrontcachePlugin {
		@Override
		public void start(PluginContext pluginContext) {
		}

		@Override
		public PluginEndpoint endpoint() {
			return (req, res) -> res.writer().write("should never be reached");
		}
	}

	@Test
	public void aStartedPluginServesItsPathAndOffersItsProviders() throws IOException {
		install(plugin("chal", "id = challenge\nclass = " + Challenger.class.getName()
				+ "\nversion = 1.0.0\nplugin-api = 1\nkinds = serve, observe\ncookies = fc_pass\nchallenges = pow\n"),
				plugin("sneaky", "id = sneaky\nclass = " + UndeclaredServer.class.getName()
						+ "\nversion = 1.0.0\nplugin-api = 1\nkinds = manage\n"));

		FrontcachePlugins.start("www.example.com", List::of);

		assertTrue(PluginEndpoints.isRunning());
		assertEquals("challenge", PluginEndpoints.challenge("pow").plugin());
		assertEquals(Set.of("fc_pass"), PluginEndpoints.challenge("pow").cookies());
		assertNull(PluginEndpoints.challenge("turnstile"));
		assertNotNull("a serving plugin with a cookie gets the signer", Challenger.context.evidenceSigner());

		ServletFakes.Response response = serve(new ServletFakes.Request().uri("/fc-x/challenge/"));
		assertEquals("hello from challenge", response.text());
		assertEquals(404, serve(new ServletFakes.Request().uri("/fc-x/sneaky/")).status);

		PluginStats stats = FrontcachePlugins.stats("challenge");
		assertTrue(stats.isServes());
		assertEquals(List.of("fc_pass"), stats.getCookies());
		assertEquals(List.of("pow"), stats.getChallenges());
		assertEquals(1, stats.getServed());
		PluginStats sneaky = FrontcachePlugins.stats("sneaky");
		assertEquals("refused", sneaky.getState());
		assertTrue(sneaky.getReason(), sneaky.getReason().contains("'serve'"));

		FrontcachePlugins.stop(false);
		assertFalse(PluginEndpoints.isRunning());
		assertNull(PluginEndpoints.challenge("pow"));
		assertEquals("a stopped plugin's path is a 404", 404, serve(new ServletFakes.Request().uri("/fc-x/challenge/")).status);
	}

	// ---- helpers ---------------------------------------------------------------------------------------------------

	private File plugin(String dir, String manifest) throws IOException {
		File root = folder.newFolder(dir);
		File file = new File(root, PluginManifest.RESOURCE);
		file.getParentFile().mkdirs();
		Files.writeString(file.toPath(), manifest, StandardCharsets.UTF_8);
		return root;
	}

	private void install(File... roots) throws IOException {
		URL[] urls = new URL[roots.length];
		for (int i = 0; i < roots.length; i++)
			urls[i] = roots[i].toURI().toURL();
		FrontcachePlugins.install(FrontcachePlugins.load(new URLClassLoader(urls, getClass().getClassLoader())));
	}

	/** A PluginRequest the node did not hand over. */
	private static final class ForeignRequest implements PluginRequest {
		@Override public String method() { return "GET"; }
		@Override public String path() { return "/"; }
		@Override public String queryString() { return ""; }
		@Override public Map<String, List<String>> queryParameters() { return Map.of(); }
		@Override public Map<String, List<String>> formParameters() { return Map.of(); }
		@Override public java.io.InputStream body() { return java.io.InputStream.nullInputStream(); }
		@Override public String header(String name) { return null; }
		@Override public Set<String> headerNames() { return Set.of(); }
		@Override public String cookie(String name) { return null; }
		@Override public String clientAddress() { return "198.51.100.1"; }
		@Override public String clientType() { return "guest"; }
		@Override public String requestId() { return "x"; }
		@Override public String scheme() { return "https"; }
	}
}
