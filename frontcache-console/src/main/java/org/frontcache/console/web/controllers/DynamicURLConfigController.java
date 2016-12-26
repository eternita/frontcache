package org.frontcache.console.web.controllers;

import java.util.List;
import java.util.Map;

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
    	
    	Map<String, List<String>> dynamicURLsConfigs = frontcacheService.getDynamicURLsConfigs();
		model.put("dynamicURLsConfigs", dynamicURLsConfigs);
    	
        return "dynamic_urls_configs";
    }
    
    
}
