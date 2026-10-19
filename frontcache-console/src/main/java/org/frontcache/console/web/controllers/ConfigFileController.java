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

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.io.ConfigFile;
import org.frontcache.io.SetConfigActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Every Configs screen: the raw config file of each edge, as it sits on disk, and the Save that
 * writes it back.
 *
 * <h2>One controller for five screens</h2>
 *
 * This replaces {@code FallbackConfigController}, {@code BotConfigController},
 * {@code DynamicURLConfigController}, {@code GuardConfigController} and
 * {@code ResilienceConfigController}, which were the same nine lines five times. All five URLs are
 * unchanged, including the pre-2.7 {@code /hystrix-configs} alias - a person can have any of them
 * bookmarked.
 *
 * <h2>Why the raw file, still</h2>
 *
 * These screens render the file rather than a parsed view on purpose: a commented-out rule and a
 * line the parser skipped are visible in the file and invisible in a table of entries. That is the
 * whole point of the split between a Configs tab and its parsed screen, so the editor is a textarea
 * over the same text, not a form.
 */
@Controller
public class ConfigFileController {

	@Autowired
	private FrontcacheService frontcacheService;

	@RequestMapping(value = "/fallbacks", method = RequestMethod.GET)
	public String fallbacks(ModelMap model) {
		return screen(model, ConfigFile.FALLBACKS);
	}

	@RequestMapping(value = "/bot-configs", method = RequestMethod.GET)
	public String bots(ModelMap model) {
		return screen(model, ConfigFile.BOTS);
	}

	@RequestMapping(value = "/dynamic-urls-configs", method = RequestMethod.GET)
	public String dynamicURLs(ModelMap model) {
		return screen(model, ConfigFile.DYNAMIC_URLS);
	}

	@RequestMapping(value = "/guard-configs", method = RequestMethod.GET)
	public String guardRules(ModelMap model) {
		return screen(model, ConfigFile.GUARD_RULES);
	}

	/**
	 * Both paths are mapped: /hystrix-configs is what this page was called before 2.7, and it is a URL
	 * a person can have bookmarked or linked to, so it keeps resolving rather than 404ing.
	 */
	@RequestMapping(value = { "/resilience-configs", "/hystrix-configs" }, method = RequestMethod.GET)
	public String resilience(ModelMap model) {
		return screen(model, ConfigFile.RESILIENCE);
	}

	/**
	 * Renders one Configs screen. Shared by the five GETs and by the POST, so the page an operator
	 * sees after a Save is built by exactly the same code as the page they saved from.
	 */
	private String screen(ModelMap model, ConfigFile configFile) {

		model.put("configFileId", configFile.getId());
		model.put("configFileName", configFile.getFileName());
		model.put("activeConfig", activeConfigFor(configFile));
		model.put("configEdges", frontcacheService.getConfigFiles(configFile));
		model.put("configEditingEnabled", frontcacheService.isConfigEditingEnabled());

		return "config_file";
	}

	/**
	 * Writes the edited file to one edge, or to all of them, and re-renders the screen.
	 *
	 * <h2>POST, and that is load-bearing</h2>
	 *
	 * {@code SameOriginFilter} only checks state-changing methods, and Basic credentials are attached
	 * by the browser automatically and are not cookies, so {@code SameSite} does not protect them. A
	 * GET that rewrote a fleet's config would be reachable from any page the authenticated operator
	 * happens to have open - the same reasoning that moved {@code add-edge} to POST in 2.9.0.
	 *
	 * <h2>The screen is re-read from the nodes afterwards</h2>
	 *
	 * Not re-rendered from the text that was posted. The two differ whenever the node normalized
	 * something (CRLF, see {@code ConfigFileStore.normalize}), and what the operator needs to see next
	 * is what is on disk.
	 */
	@RequestMapping(value = "/save-config", method = RequestMethod.POST)
	public String save(ModelMap model, HttpServletRequest request) {

		ConfigFile configFile = ConfigFile.byId(request.getParameter("file"));
		if (null == configFile)
			return "redirect:fallbacks";

		String content = request.getParameter("content");
		String baseHash = request.getParameter("baseHash");
		String edgeURL = request.getParameter("edge");
		boolean allEdges = "all".equals(request.getParameter("scope"));

		Map<String, SetConfigActionResponse> results;

		if (allEdges)
			results = frontcacheService.saveConfigFileToAllEdges(configFile, content, baseHash, edgeURL);
		else
		{
			results = new LinkedHashMap<String, SetConfigActionResponse>();
			results.put(edgeURL, frontcacheService.saveConfigFile(edgeURL, configFile, content, baseHash));
		}

		model.put("saveResults", results);

		// The tab the operator was on, so the re-rendered page opens where they left it rather than on
		// the first edge.
		model.put("activeEdgeURL", edgeURL);

		return screen(model, configFile);
	}

	/** Which pill config_menu.jsp highlights. Console presentation, so it lives here and not on the enum. */
	private static String activeConfigFor(ConfigFile configFile) {
		switch (configFile)
		{
		case FALLBACKS:
			return "fallbacks";
		case BOTS:
			return "bot";
		case DYNAMIC_URLS:
			return "dynamic";
		case GUARD_RULES:
			return "guard";
		case RESILIENCE:
			return "resilience";
		}
		return "";
	}

}
