/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import java.util.Map;
import java.util.Set;

import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
@RequestMapping("/cache-invalidation")
public class CacheInvalidationController {
	
	@Autowired
	private FrontcacheService frontcacheService;


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model) {
		CacheInvalidationForm cacheInvalidationForm = new CacheInvalidationForm();
		model.addAttribute("cacheInvalidation", cacheInvalidationForm);

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);
		
		Map<String, FrontCacheStatus> clusterStatus = frontcacheService.getClusterStatus();
		model.addAttribute("edges", clusterStatus.values());
    	
		return "cache_invalidation";
	}	
    
	
	@RequestMapping(method = RequestMethod.POST)
	public String submitForm(Model model, CacheInvalidationForm cacheInvalidationForm) {
		model.addAttribute("cacheInvalidation", cacheInvalidationForm);
		
		frontcacheService.invalidateEdge(cacheInvalidationForm.getEdge(), cacheInvalidationForm.getFilter());

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);
		
		Map<String, FrontCacheStatus> clusterStatus = frontcacheService.getClusterStatus();
		model.addAttribute("edges", clusterStatus.values());
		
		return "cache_invalidation";
	}	
}
