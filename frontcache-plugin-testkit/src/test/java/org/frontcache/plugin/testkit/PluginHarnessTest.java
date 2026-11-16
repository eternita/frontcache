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
package org.frontcache.plugin.testkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.io.ActionResponse;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.ManagementRequest;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.event.RateLimitExceeded;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.junit.Test;

import com.typesafe.config.Config;

public class PluginHarnessTest {

	/** Records its life cycle. */
	public static class Recorder implements FrontcachePlugin {
		final List<String> calls = new ArrayList<>();
		final List<FcEvent> events = new ArrayList<>();
		String setting = "default";

		@Override
		public void configure(Config config) throws PluginConfigException {
			if (config.hasPath("bad"))
				throw new PluginConfigException("bad is bad");
			setting = config.hasPath("setting") ? config.getString("setting") : "default";
			calls.add("configure " + setting);
		}

		@Override
		public void start(PluginContext context) {
			calls.add("start");
		}

		@Override
		public void stop(boolean shutdown) {
			calls.add("stop " + shutdown);
		}

		@Override
		public List<EventListener> eventListeners() {
			return List.of(new EventListener() {
				@Override
				public Set<Class<? extends FcEvent>> subscribes() {
					return Set.of(GuardMatched.class);
				}

				@Override
				public void onEvents(List<FcEvent> batch) {
					events.addAll(batch);
				}
			});
		}

		@Override
		public PluginEndpoint endpoint() {
			return (request, response) -> {
				response.contentType("text/plain");
				response.writer().print(request.path() + " " + request.parameter("x") + " " + request.cookie("fc_pass")
						+ " " + request.cookie("other") + " " + request.header("Authorization"));
			};
		}

		@Override
		public Map<String, ManagementAction> managementActions() {
			return Map.of("recorder-status", new ManagementAction() {
				@Override
				public String description() {
					return "status";
				}

				@Override
				public ActionResponse handle(ManagementRequest request) {
					ActionResponse r = new ActionResponse();
					r.setResponseStatus(request.isAuthorizedToWrite() ? "OK" : "ERROR");
					return r;
				}
			});
		}
	}

	private static PluginHarness recorder(String kinds) {
		return PluginHarness.of(new Recorder(), PluginHarness.manifest("id = recorder\nclass = " + Recorder.class.getName()
				+ "\nversion = 1\nplugin-api = 1\nkinds = " + kinds + "\nconfig = recorder.conf\n"
				+ (List.of(kinds.split("\\s*,\\s*")).contains("serve") ? "cookies = fc_pass\n" : "")));
	}

	@Test
	public void itIsConfiguredBeforeEveryStartAndReloadIsStopThenStart() throws Exception {
		try (PluginHarness h = recorder("observe, serve, manage")) {
			Recorder r = (Recorder) h.plugin();
			h.start();
			h.configure("setting = one");
			h.reload();
			h.close();
			assertEquals(List.of("configure default", "start", "configure one", "stop false", "configure one", "start",
					"stop true"), r.calls);
		}
	}

	@Test
	public void aRefusedConfigKeepsThePreviousOne() throws Exception {
		try (PluginHarness h = recorder("observe, serve, manage")) {
			h.configure("setting = one");
			try {
				h.configure("bad = 1");
				fail();
			} catch (PluginConfigException expected) {
			}
			h.start();
			assertEquals("one", ((Recorder) h.plugin()).setting);
		}
	}

	@Test
	public void aKindItDoesNotDeclareIsRefused() throws Exception {
		for (String kinds : new String[] { "serve, manage", "observe, manage", "observe, serve" })
		{
			try (PluginHarness h = recorder(kinds)) {
				h.start();
				fail("started with kinds " + kinds);
			} catch (PluginRefusedException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("without declaring"));
			}
		}
	}

	@Test
	public void eventsReachTheListenersSubscribedToTheirType() throws Exception {
		try (PluginHarness h = recorder("observe, serve, manage")) {
			h.start();
			int delivered = h.deliver(SampleEvents.of(GuardMatched.class).get(0),
					SampleEvents.of(RateLimitExceeded.class).get(0));
			assertEquals(1, delivered);
			assertEquals(1, ((Recorder) h.plugin()).events.size());
			assertEquals(4, h.deliver(SampleEvents.of(GuardMatched.class)));
		}
	}

	@Test
	public void theEndpointSeesTheNarrowedView() throws Exception {
		try (PluginHarness h = recorder("observe, serve, manage")) {
			h.start();
			FakePluginResponse r = h.serve(FakePluginRequest.get("/page?x=1").cookie("fc_pass", "p").cookie("other", "o")
					.header("Authorization", "Basic x"));
			assertEquals("/page 1 p null null", r.text());
			assertEquals(200, r.status());
		}
	}

	@Test
	public void theResponseIsNarrowedAsTheNodesIs() {
		FakePluginResponse r = new FakePluginResponse(PluginHarness.manifest(
				"id = xy\nclass = a.B\nversion = 1\nplugin-api = 1\nkinds = serve\ncookies = fc_pass\n"), "www.example.com",
				Set.of("shop.example.net"), "https");
		for (String header : new String[] { "Set-Cookie", "Location", "Cache-Control", "X-Anything" })
		{
			try {
				r.header(header, "v");
				fail(header);
			} catch (IllegalArgumentException expected) {
			}
		}
		try {
			r.header("Content-Type", "text/plain\r\nX-Injected: 1");
			fail();
		} catch (IllegalArgumentException expected) {
		}
		assertEquals("/cart", r.redirect(303, "/cart"));
		assertEquals("/", r.redirect(302, "//evil.example.com/"));
		assertEquals("/", r.redirect(302, "https://evil.example.com/"));
		assertEquals("/", r.redirect(302, "javascript:alert(1)"));
		assertEquals("https://www.example.com/x", r.redirect(302, "https://www.example.com/x"));
		assertEquals("https://shop.example.net/x", r.redirect(302, "https://shop.example.net/x"));
		try {
			r.redirect(200, "/");
			fail();
		} catch (IllegalArgumentException expected) {
		}
		r.cookie("fc_pass", "1900000000.abc", Duration.ofHours(1));
		assertTrue(r.cookies().get("fc_pass").secure());
		for (String[] bad : new String[][] { { "other", "v" }, { "fc_pass", "a b" }, { "fc_pass", "a;b" } })
		{
			try {
				r.cookie(bad[0], bad[1], Duration.ofHours(1));
				fail(bad[0] + "=" + bad[1]);
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void aRequestBodyOverTheLimitThrows() throws Exception {
		FakePluginRequest r = FakePluginRequest.form("/", "a=" + "x".repeat(100)).maxBody(50);
		try {
			r.formParameters();
			fail();
		} catch (java.io.IOException expected) {
		}
		assertEquals(List.of("2"), FakePluginRequest.form("/", "a=1&a=2&b=%C3%A9").formParameters().get("a").subList(1, 2));
		assertEquals("é", FakePluginRequest.form("/", "b=%C3%A9").formParameters().get("b").get(0));
		assertTrue(FakePluginRequest.post("/", "application/json", "{}").formParameters().isEmpty());
	}

	@Test
	public void actionsAreCalledPastTheKeyGate() throws Exception {
		try (PluginHarness h = recorder("observe, serve, manage")) {
			h.start();
			assertEquals("ERROR", h.call("recorder-status").getResponseStatus());
			assertEquals("OK", h.call(FakeManagementRequest.of("recorder-status").writeAuthorized(true))
					.getResponseStatus());
			try {
				h.call("nothing");
				fail();
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void theManifestThatNamesTheClassIsPicked() {
		// the examples' manifests are on this class path, one per resource root, as one per jar on a node
		assertEquals("example", PluginHarness.manifestOf(com.example.frontcache.ExamplePlugin.class).id());
		assertEquals("debug", PluginHarness.manifestOf(org.frontcache.examples.debug.DebugPlugin.class).id());
		assertEquals("crawler-feeder",
				PluginHarness.manifestOf(org.frontcache.examples.crawlerfeeder.CrawlerFeederPlugin.class).id());
		try {
			PluginHarness.manifestOf(Recorder.class);
			fail();
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("no META-INF/frontcache-plugin.properties"));
		}
	}

	@Test
	public void theEmptyViewIsAllNull() {
		var v = RequestViews.empty();
		assertNull(v.method());
		assertNull(v.uri());
		assertTrue(v.cookieNames().isEmpty());
		assertNull(v.header("User-Agent"));
		assertNull(RequestViews.get("/").header("Authorization", "x").build().header("authorization"));
		assertEquals("b", RequestViews.get("/").header("X-A", "b").build().header("x-a"));
		assertFalse(RequestViews.corpus().isEmpty());
	}
}
