package org.frontcache.console.web.controllers;

import java.util.Map;
import java.util.Set;

import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
public class DynamicURLConfigController {
	
	@Autowired
	private FrontcacheService frontcacheService;
	
    @RequestMapping(value = "/dynamic-urls-configs", method = RequestMethod.GET)
    public String getDynamicURLConfigs(ModelMap model) {
    	
    	Map<String, Set<String>> dynamicURLsConfigs = frontcacheService.getDynamicURLsConfigs();
		model.put("dynamicURLsConfigs", dynamicURLsConfigs);
    	
        return "dynamic_urls_configs";
    }
    
    
}
