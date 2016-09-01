package org.frontcache.console.web.controllers;

import java.util.Map;

import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
public class FrontcacheStatusController {
	
	@Autowired
	private FrontcacheService frontcacheService;
	

    @RequestMapping(value = "/", method = RequestMethod.GET)
    public String domainOverview(ModelMap model) {
    	
    	Map<String, FrontCacheStatus> clusterStatus = frontcacheService.getClusterStatus();
		model.put("edges", clusterStatus.values());

        return "status";
    }
    
    @RequestMapping(value = "/realtime", method = RequestMethod.GET)
    public String domainRealtimeMonitor(ModelMap model) {
    	
    	Map<String, FrontCacheStatus> clusterStatus = frontcacheService.getClusterStatus();
		model.put("edges", clusterStatus.values());
		
		model.put("hystrixMonitorURLList", frontcacheService.getHystrixMonitorURLList());

        return "realtime";
    }
    
 
}
