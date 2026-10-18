/*
 * Copyright 2015 Eternita
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
package org.frontcache.console.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.controllers.IndexController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.XmlWebApplicationContext;

/**
 * Both Spring contexts of the console, built from the real XML exactly as web.xml builds them, with NO
 * plugin on the class path - the Community console of docs/ip-protection-apache-core-and-prop-pro-proposal.md
 * (principle 3: a missing Pro jar is a non-event). frontcache-pro-console's {@code ConsolePluginWiringTest}
 * is the same two contexts with the Pro plugin.
 *
 * The plugin hooks must all be present and all be empty: no menu entry, script, stylesheet, notice or
 * stream contributor, and no license or warmer bean - the pages render them from these and nothing else.
 */
public class ConsoleWithoutPluginsTest {

	private XmlWebApplicationContext root;
	private XmlWebApplicationContext web;
	private MockServletContext servletContext;

	@Before
	public void startContexts() {
		servletContext = new MockServletContext();

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

	@Test
	public void noPluginIsFound() {
		assertEquals(List.of(), ConsolePlugins.plugins());
	}

	@Test
	public void theCoreStartsWithEveryHookEmpty() {
		assertEquals(1, root.getBeanNamesForType(FrontcacheService.class).length);
		assertEquals(1, web.getBeanNamesForType(IndexController.class).length);

		assertEquals(0, root.getBeansOfType(DashboardStreamContributor.class).size());
		assertEquals(0, web.getBeansOfType(ConsoleNoticeProvider.class).size());

		ConsoleNotices notices = web.getBean(ConsoleNoticesAdvice.class).consoleNotices();
		assertEquals(List.of(), notices.getBanners());
		assertEquals(List.of(), notices.getFooterLines());
	}

	@Test
	public void theJspsGetNoMenuEntryScriptOrStylesheet() {
		ConsolePlugins.View view = (ConsolePlugins.View) servletContext.getAttribute("fcPlugins");
		assertNotNull("set by WebMVCConfig", view);
		assertEquals(List.of(), view.getTopNav());
		assertEquals(List.of(), view.getCacheNav());
		assertEquals(List.of(), view.getDashboardScripts());
		assertEquals(List.of(), view.getStylesheets());
	}

	@Test
	public void noLicenseOrWarmerBeanExists() {
		List<String> pro = new ArrayList<String>();
		for (XmlWebApplicationContext ctx : List.of(root, web))
			for (String name : ctx.getBeanDefinitionNames())
			{
				String type = String.valueOf(ctx.getType(name)).toLowerCase(Locale.ROOT);
				if (type.contains("license") || type.contains("warmer") || type.contains(".pro."))
					pro.add(name + " (" + type + ")");
			}
		assertTrue("Pro beans in a console with no plugin: " + pro, pro.isEmpty());
	}
}
