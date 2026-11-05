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
package org.frontcache.console.web.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.frontcache.console.service.FrontcacheService;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ModelMap;

/**
 * {@code edge-editing = false} (2.12.0): a console whose edge list is written by something else - an
 * inventory, a control plane - refuses to change it, and does not offer to. The key's parsing is
 * pinned in {@code ConfigFileScreenTest}.
 */
public class EdgeEditingTest {

	private FrontcacheService service(boolean edgeEditing) {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		when(service.isEdgeEditingEnabled()).thenReturn(edgeEditing);
		return service;
	}

	private EdgesController controller(FrontcacheService service) {
		EdgesController controller = new EdgesController();
		ReflectionTestUtils.setField(controller, "frontcacheService", service);
		return controller;
	}

	@Test
	public void addAndRemoveAreRefusedWhenOff() throws Exception {
		FrontcacheService service = service(false);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/add-edge");
		request.setParameter("edges", "https://elsewhere.example.com/");
		request.setParameter("edge", "https://n1.example.com/");

		MockHttpServletResponse added = new MockHttpServletResponse();
		assertNull(controller(service).addEdge(request, added));
		assertEquals(403, added.getStatus());

		MockHttpServletResponse removed = new MockHttpServletResponse();
		assertNull(controller(service).removeEdge(request, removed));
		assertEquals(403, removed.getStatus());

		verify(service, never()).addEdge(anyString());
		verify(service, never()).removeEdge(anyString());
	}

	@Test
	public void addAndRemoveWorkWhenOn() throws Exception {
		FrontcacheService service = service(true);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/add-edge");
		request.setParameter("edges", "https://a.example.com/ https://b.example.com/");
		request.setParameter("edge", "https://a.example.com/");

		assertEquals("redirect:edges", controller(service).addEdge(request, new MockHttpServletResponse()));
		verify(service).addEdge("https://a.example.com/");
		verify(service).addEdge("https://b.example.com/");

		assertEquals("redirect:edges", controller(service).removeEdge(request, new MockHttpServletResponse()));
		verify(service).removeEdge("https://a.example.com/");
	}

	@Test
	public void theScreenSaysWhetherToOfferIt() {
		ModelMap model = new ModelMap();
		controller(service(false)).domainRealtimeMonitor(model);
		assertFalse((Boolean) model.get("edgeEditingEnabled"));

		model = new ModelMap();
		controller(service(true)).domainRealtimeMonitor(model);
		assertTrue((Boolean) model.get("edgeEditingEnabled"));
	}

	/** With no edges, `/` adds a local one by itself - never on a console whose list is not its own. */
	@Test
	public void theRootAddsNoLocalEdgeWhenOff() {
		FrontcacheService service = service(false);
		IndexController index = new IndexController();
		ReflectionTestUtils.setField(index, "frontcacheService", service);

		assertEquals("no_edges", index.index(new MockHttpServletRequest("GET", "/")));
		verify(service, never()).isFrontCacheEdgeAvailable(anyString());
		verify(service, never()).addEdge(anyString());
	}
}
