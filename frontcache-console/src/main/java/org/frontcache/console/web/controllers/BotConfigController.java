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
public class BotConfigController {
	
	@Autowired
	private FrontcacheService frontcacheService;
	
    @RequestMapping(value = "/bot-configs", method = RequestMethod.GET)
    public String getBotConfigs(ModelMap model) {
    	
    	Map<String, Set<String>> botConfigs = frontcacheService.getBotConfigs();
		model.put("botConfigs", botConfigs);

        return "bot_configs";
    }
    
    
}
