package org.frontcache.console.web.controllers;

import java.util.Set;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.core.WebResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
	public String initForm(Model model) {
		CacheViewForm cacheViewForm = new CacheViewForm();
		model.addAttribute("cacheView", cacheViewForm);

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);
		
		return "cache_view";
	}	
    
	
	@RequestMapping(method = RequestMethod.POST)
	public String submitForm(Model model, CacheViewForm cacheViewForm) {
		model.addAttribute("cacheView", cacheViewForm);
		
		WebResponse webResponse = frontcacheService.getFromCache(cacheViewForm.getEdge(), cacheViewForm.getKey());
		if (null != webResponse)
		{
			model.addAttribute("webResponse", webResponse);
			model.addAttribute("webResponseStr", new String(webResponse.getContent()));
			model.addAttribute("webResponseHeaders", webResponse.getHeaders());
		}

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);
		
		return "cache_view";
	}	
}
