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
