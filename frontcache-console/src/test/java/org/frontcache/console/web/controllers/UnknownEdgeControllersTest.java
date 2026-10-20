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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.service.UnknownEdgeException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

/**
 * The cache screens that name one edge: when the service refuses an edge that is not one of this
 * console's, the page says so - rather than rendering the success line ("Purge started on ...") or
 * "Nothing Found", as if the edge had been asked. The refusal itself is
 * {@code FrontcacheServiceConfiguredEdgeTest}.
 */
public class UnknownEdgeControllersTest {

	private static final String UNKNOWN = "http://attacker.example.com/";
	private static final String MESSAGE = UnknownEdgeException.messageFor(UNKNOWN);

	private FrontcacheService service;
	private ExtendedModelMap model;

	@Before
	public void setUp() {
		service = Mockito.mock(FrontcacheService.class);
		UnknownEdgeException refusal = new UnknownEdgeException(UNKNOWN);

		when(service.getFromCache(Mockito.eq(UNKNOWN), any())).thenThrow(refusal);
		doThrow(refusal).when(service).invalidateEdge(Mockito.eq(UNKNOWN), any());
		doThrow(refusal).when(service).purgeEdge(UNKNOWN);
		when(service.dumpKeys(UNKNOWN)).thenThrow(refusal);
		when(service.backupEdge(UNKNOWN)).thenThrow(refusal);
		when(service.startReplication(anyString(), Mockito.eq(UNKNOWN))).thenThrow(refusal);

		model = new ExtendedModelMap();
	}

	private <T> T controller(T controller) {
		ReflectionTestUtils.setField(controller, "frontcacheService", service);
		return controller;
	}

	@Test
	public void cacheView() {
		CacheViewForm form = new CacheViewForm();
		form.setEdge(UNKNOWN);
		form.setKey("http://site/page");

		assertEquals("cache_view", controller(new CacheViewController()).submitForm(model, form));
		assertEquals(MESSAGE, model.get("edgeError"));
		assertFalse("not 'Nothing Found'", model.containsAttribute("submitted"));
	}

	@Test
	public void cacheInvalidation() {
		CacheInvalidationController controller = controller(new CacheInvalidationController());
		CacheInvalidationForm form = new CacheInvalidationForm();
		form.setEdge(UNKNOWN);
		form.setFilter("*");

		controller.submitForm(model, form);
		assertEquals(MESSAGE, model.get("edgeError"));

		model = new ExtendedModelMap();
		controller.purge(model, form);
		assertEquals(MESSAGE, model.get("edgeError"));
		assertFalse(model.containsAttribute("purgeResult"));
	}

	@Test
	public void cacheOther() {
		CacheOtherController controller = controller(new CacheOtherController());
		CacheOtherForm form = new CacheOtherForm();
		form.setEdge(UNKNOWN);

		controller.dumpKeys(model, form);
		assertEquals(MESSAGE, model.get("edgeError"));
		assertFalse(model.containsAttribute("dumpKeysResult"));

		model = new ExtendedModelMap();
		controller.purge(model, form);
		assertEquals(MESSAGE, model.get("edgeError"));
		assertFalse(model.containsAttribute("purgeResult"));

		model = new ExtendedModelMap();
		controller.backupIndex(model, form);
		assertEquals(MESSAGE, model.get("edgeError"));
		assertFalse(model.containsAttribute("backupResult"));
	}

	@Test
	public void cacheReplication() {
		CacheReplicationForm form = new CacheReplicationForm();
		form.setSourceEdge("http://localhost:9080/");
		form.setTargetEdge(UNKNOWN);

		controller(new CacheReplicationController()).startReplication(model, form);
		assertEquals(MESSAGE, model.get("replicationError"));
		assertFalse(model.containsAttribute("replicationMessage"));
	}
}
