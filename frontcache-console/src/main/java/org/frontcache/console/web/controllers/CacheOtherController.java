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

import org.frontcache.console.auth.ConsoleAudit;
import org.frontcache.console.auth.ConsoleAuditor;
import org.frontcache.console.auth.ConsolePermissions;
import org.frontcache.console.auth.ConsolePermit;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.service.UnknownEdgeException;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
@RequestMapping("/cache-other")
public class CacheOtherController {

	@Autowired
	private FrontcacheService frontcacheService;


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model, EdgeScope scope) {
		CacheOtherForm cacheOtherForm = new CacheOtherForm();
		model.addAttribute("cacheOther", cacheOtherForm);

    	List<String> agents = scope.edgeURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}


	@ConsolePermit(ConsolePermissions.CACHE_READ)
	@ConsoleAudit("cache.dump-keys")
	@RequestMapping(method = RequestMethod.POST, params = "dumpKeys")
	public String dumpKeys(Model model, CacheOtherForm cacheOtherForm, EdgeScope scope, HttpServletRequest request) {
		model.addAttribute("cacheOther", cacheOtherForm);

		try {
			String dumpKeysResult = frontcacheService.dumpKeys(scope, cacheOtherForm.getEdge());
			model.addAttribute("dumpKeysResult", dumpKeysResult);
		} catch (UnknownEdgeException e) {
			ConsoleAuditor.outcome(request, ConsoleAuditor.REFUSED);
			model.addAttribute("edgeError", e.getMessage());
		}

    	List<String> agents = scope.edgeURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}


	@ConsolePermit(ConsolePermissions.CACHE_INVALIDATE)
	@ConsoleAudit("cache.purge")
	@RequestMapping(method = RequestMethod.POST, params = "purge")
	public String purge(Model model, CacheOtherForm cacheOtherForm, EdgeScope scope, HttpServletRequest request) {
		model.addAttribute("cacheOther", cacheOtherForm);

		try {
			frontcacheService.purgeEdge(scope, cacheOtherForm.getEdge());
			model.addAttribute("purgeResult", "Purge of expired entries started on " + cacheOtherForm.getEdge());
		} catch (UnknownEdgeException e) {
			ConsoleAuditor.outcome(request, ConsoleAuditor.REFUSED);
			model.addAttribute("edgeError", e.getMessage());
		}

    	List<String> agents = scope.edgeURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}


	@ConsolePermit(ConsolePermissions.CACHE_REPLICATE)
	@ConsoleAudit("cache.backup-index")
	@RequestMapping(method = RequestMethod.POST, params = "backupIndex")
	public String backupIndex(Model model, CacheOtherForm cacheOtherForm, EdgeScope scope, HttpServletRequest request) {
		model.addAttribute("cacheOther", cacheOtherForm);

		try {
			String backupResult = frontcacheService.backupEdge(scope, cacheOtherForm.getEdge());
			model.addAttribute("backupResult", backupResult + " on " + cacheOtherForm.getEdge());
		} catch (UnknownEdgeException e) {
			ConsoleAuditor.outcome(request, ConsoleAuditor.REFUSED);
			model.addAttribute("edgeError", e.getMessage());
		}

    	List<String> agents = scope.edgeURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}
}
