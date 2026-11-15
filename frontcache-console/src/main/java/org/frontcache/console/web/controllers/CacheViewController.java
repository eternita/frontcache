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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.frontcache.console.auth.ConsoleAudit;
import org.frontcache.console.auth.ConsoleAuditor;
import org.frontcache.console.auth.ConsolePermissions;
import org.frontcache.console.auth.ConsolePermit;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.model.CacheEntryExpiry;
import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.service.UnknownEdgeException;
import org.frontcache.bots.VisitorTypeEntry;
import org.frontcache.core.WebResponse;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
@RequestMapping("/cache-view")
public class CacheViewController {

	@Autowired
	private FrontcacheService frontcacheService;


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model, EdgeScope scope) {
		CacheViewForm cacheViewForm = new CacheViewForm();
		model.addAttribute("cacheView", cacheViewForm);

    	List<String> agents = scope.edgeURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_view";
	}


	@ConsolePermit(ConsolePermissions.CACHE_READ)
	@ConsoleAudit(value = "cache.view", parameters = "key")
	@RequestMapping(method = RequestMethod.POST, params = "viewCache")
	public String submitForm(Model model, CacheViewForm cacheViewForm, EdgeScope scope, HttpServletRequest request) {
		model.addAttribute("cacheView", cacheViewForm);

		WebResponse webResponse;
		try {
			webResponse = frontcacheService.getFromCache(scope, cacheViewForm.getEdge(), cacheViewForm.getKey());
		} catch (UnknownEdgeException e) {
			ConsoleAuditor.outcome(request, ConsoleAuditor.REFUSED);
			// not "submitted": the page would otherwise say "Nothing Found", as if the edge had been asked
			model.addAttribute("edgeError", e.getMessage());
			model.addAttribute("edgeList", scope.edgeURLs());
			return "cache_view";
		}
		model.addAttribute("submitted", true);

		if (null != webResponse)
		{
			model.addAttribute("webResponse", webResponse);
			model.addAttribute("webResponseStr", new String(webResponse.getContent()));
			Map<String, List<String>> webRespHeaders = new TreeMap<String, List<String>>();
			webRespHeaders.putAll(webResponse.getHeaders());
			model.addAttribute("webResponseHeaders", webRespHeaders);

			model.addAttribute("invalidationTags", webResponse.getTags());

			// A row per type the edge knows, with the stamp its requests resolve to - a declared type the page did
			// not name reads its base's, and listing the stored keys alone would show it as not cached
			// (CacheEntryExpiry). The edge is the one getFromCache was just allowed to read.
			List<VisitorTypeEntry> types = frontcacheService.getVisitorTypeEntries(scope, cacheViewForm.getEdge());
			model.addAttribute("expiryRows", CacheEntryExpiry.rows(webResponse.getExpireTimeMap(), types,
					System.currentTimeMillis()));

		}

    	List<String> agents = scope.edgeURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_view";
	}
}
