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
package org.frontcache.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.StringReader;
import java.util.EnumSet;
import java.util.Properties;

import org.junit.Test;

public class PluginManifestTest {

	private static Properties props(String text) throws IOException {
		Properties p = new Properties();
		p.load(new StringReader(text));
		return p;
	}

	private static final String VALID = "id = alerts\nclass = com.example.Alerts\nversion = 1.0.0\n"
			+ "plugin-api = 1\nkinds = observe, manage\n";

	@Test
	public void aCompleteManifestParses() throws IOException {
		PluginManifest m = PluginManifest.parse(props(VALID + "name = Alerts\nvendor = Example\nlicense = Apache-2.0\n"
				+ "config = alerts.conf\n"), "test");
		assertEquals("alerts", m.id());
		assertEquals("com.example.Alerts", m.entryClass());
		assertEquals("Alerts", m.name());
		assertEquals("1.0.0", m.version());
		assertEquals("Example", m.vendor());
		assertEquals("Apache-2.0", m.license());
		assertEquals(1, m.pluginApi());
		assertEquals(EnumSet.of(PluginKind.OBSERVE, PluginKind.MANAGE), m.kinds());
		assertEquals("observe, manage", m.kindList());
		assertEquals("alerts.conf", m.config());
		assertFalse(m.isRequestPath());
		assertTrue(m.warnings().isEmpty());
	}

	@Test
	public void theNameDefaultsToTheId() throws IOException {
		assertEquals("alerts", PluginManifest.parse(props(VALID), "test").name());
	}

	@Test
	public void everyReasonIsReportedAtOnce() throws IOException {
		try {
			PluginManifest.parse(props("id = Bad_Id\nplugin-api = x\nkinds = observe, teleport\nconfig = ../etc/passwd\n"),
					"test");
			fail("refused");
		} catch (IllegalArgumentException e) {
			String message = e.getMessage();
			assertTrue(message, message.contains("'id' must match"));
			assertTrue(message, message.contains("'class' is missing"));
			assertTrue(message, message.contains("'version' is missing"));
			assertTrue(message, message.contains("'plugin-api' must be a major version number"));
			assertTrue(message, message.contains("unknown kind 'teleport'"));
			assertTrue(message, message.contains("'config' must be a plain .conf file name"));
		}
	}

	@Test
	public void aTrailingCommentIsPartOfTheValueAndRefused() throws IOException {
		// .properties has no trailing comments - the proposal's first example had them
		try {
			PluginManifest.parse(props(VALID.replace("id = alerts", "id = alerts   # the config file")), "test");
			fail("refused");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("'id' must match"));
		}
	}

	@Test
	public void aConfigIsAPlainConfFileName() throws IOException {
		for (String config : new String[] { "alerts.properties", "frontcache.properties", "conf/alerts.conf", ".conf",
				"alerts.conf.bak" })
		{
			try {
				PluginManifest.parse(props(VALID + "config = " + config + "\n"), "test");
				fail(config + " refused");
			} catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("'config' must be a plain .conf file name"));
			}
		}
		assertEquals("alerts-2.conf",
				PluginManifest.parse(props(VALID + "config = alerts-2.conf\n"), "test").config());
	}

	@Test
	public void kindsMustNameAtLeastOne() throws IOException {
		try {
			PluginManifest.parse(props(VALID.replace("kinds = observe, manage", "kinds = ,")), "test");
			fail("refused");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("'kinds' is empty"));
		}
	}

	@Test
	public void anUnknownKeyIsAWarningNotARefusal() throws IOException {
		PluginManifest m = PluginManifest.parse(props(VALID + "homepage = https://example.com\n"), "test");
		assertEquals(1, m.warnings().size());
		assertTrue(m.warnings().get(0), m.warnings().get(0).contains("homepage"));
	}

	@Test
	public void requestPathKindsAreBadged() throws IOException {
		assertTrue(PluginManifest.parse(props(VALID.replace("observe, manage", "decide")), "test").isRequestPath());
		assertTrue(PluginKind.DECIDE.isRequestPath());
		assertTrue(PluginKind.TRANSFORM.isRequestPath());
		assertTrue(PluginKind.REPLACE.isRequestPath());
		assertFalse(PluginKind.OBSERVE.isRequestPath());
		assertFalse(PluginKind.SERVE.isRequestPath());
		assertFalse(PluginKind.MANAGE.isRequestPath());
	}
}
