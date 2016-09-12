package org.frontcache.console.web.controllers;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.hystrix.fr.FallbackConfigEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class FallbackConfigController {
	
	@Autowired
	private FrontcacheService frontcacheService;
	
	
	
    @RequestMapping(value = "/fallbacks", method = RequestMethod.GET)
    public String fallbacks(ModelMap model) {
    	
    	Map<String, List<FallbackConfigEntry>> fallbackConfigs = frontcacheService.getFallbackConfigs();
		model.put("fallbackConfigs", fallbackConfigs);
    	
    	
		model.put("domainId", "domainIdStr");
		model.put("url", "");
		model.put("pattern", "");
		model.put("matchResult", "");

        return "fallback_settings";
    }
    
    
    
    @RequestMapping(value = "/fallbacks/urltest", method = RequestMethod.GET)
    public String urlTest(ModelMap model,
    		@RequestParam("url") String url, 
    		@RequestParam("pattern") String pattern) {
    	
    	System.out.println("param url " + url);

    	Pattern p = null;
    	try
    	{
        	p = Pattern.compile(pattern);
    	} catch (Exception ex) {
    		model.put("matchResult", "Can't comptile pattern");
    	}
    	
    	if (null != p)
    	{
        	if (p.matcher(url).matches())
        		model.put("matchResult", "URL match Pattern");
        	else
        		model.put("matchResult", "URL doesn't match Pattern");
    	}

		model.put("domainId", "domainIdStr");
		model.put("url", url);
		model.put("pattern", pattern);

        return "fallback_settings";
    }
 

}
