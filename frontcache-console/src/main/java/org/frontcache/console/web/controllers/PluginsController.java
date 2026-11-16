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

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.console.auth.ConsoleAudit;
import org.frontcache.console.auth.ConsoleAuditor;
import org.frontcache.console.auth.ConsolePermissions;
import org.frontcache.console.auth.ConsolePermit;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.service.FrontcacheService;
import org.frontcache.io.ReloadPluginConfigActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The Plugins screen (as of 2.13.0): per edge of the request's scope, every plugin its {@code get-node-stats}
 * reports - what it is, where it came from, what it declares, its state, its config's last read and its counters -
 * and one action, <b>Reload config</b> ({@code reload-plugin-config} on one edge).
 *
 * <h2>The reload is gated as a config save is</h2>
 *
 * It applies what is already on the node's disk, so the node itself gates it by the API key only. The console treats
 * it as config editing all the same: {@code config.write}, and refused while {@code config-editing} is off - an
 * operator who may not change a fleet's config should not be the one deciding when a changed file takes effect.
 */
@Controller
public class PluginsController {

	@Autowired
	private FrontcacheService frontcacheService;

	@RequestMapping(value = "/plugins", method = RequestMethod.GET)
	public String plugins(ModelMap model, EdgeScope scope) {
		return screen(model, scope);
	}

	/**
	 * Reloads one plugin's config on one edge and re-renders the screen, re-read from the nodes - so the config's
	 * status beside the answer is the node's after the reload. POST for the reason {@code /save-config} is one.
	 */
	@ConsolePermit(ConsolePermissions.CONFIG_WRITE)
	@ConsoleAudit(value = "plugin.config.reload", parameters = { "plugin" })
	@RequestMapping(value = "/plugins-reload", method = RequestMethod.POST)
	public String reload(ModelMap model, HttpServletRequest request, EdgeScope scope) {

		String edgeURL = request.getParameter("edge");
		String pluginId = request.getParameter("plugin");

		ReloadPluginConfigActionResponse result = frontcacheService.reloadPluginConfig(scope, edgeURL, pluginId);
		ConsoleAuditor.edgeOutcome(request, String.valueOf(edgeURL),
				result.isApplied() ? ConsoleAuditor.OK : ConsoleAuditor.REFUSED);

		model.put("reloadResult", result);
		model.put("reloadEdgeURL", edgeURL);
		// the tab the operator was on, so the re-rendered page opens where they left it
		model.put("activeEdgeURL", edgeURL);

		return screen(model, scope);
	}

	private String screen(ModelMap model, EdgeScope scope) {
		model.put("pluginEdges", frontcacheService.getEdgePlugins(scope));
		model.put("configEditingEnabled", frontcacheService.isConfigEditingEnabled());
		return "plugins";
	}
}
