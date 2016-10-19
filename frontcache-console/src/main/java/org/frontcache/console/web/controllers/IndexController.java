package org.frontcache.console.web.controllers;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller
public class IndexController {

	@Autowired
	private FrontcacheService frontcacheService;

	@RequestMapping(value = {"/", "/index"}, method = RequestMethod.GET)
	public String index(HttpServletRequest request) {
		
		if (frontcacheService.getEdgesAmount() > 0)
			return "redirect:realtime";
		else {

			// check if it's development / test mode 
			// edge and console started inside gretty farm
			String localFrontcacheURL = (request.isSecure() ? "https" : "http") + "://localhost:" + request.getServerPort();
			if (frontcacheService.isFrontCacheEdgeAvailable(localFrontcacheURL))
			{
				// add farm edge
				frontcacheService.addEdge(localFrontcacheURL);
				return "redirect:realtime";
			}
			
			return "no_edges";
		}
		
	}

	
	
	@RequestMapping(value = "/realtime", method = RequestMethod.GET)
	public String domainRealtimeMonitor(ModelMap model) {

		Map<String, FrontCacheStatus> clusterStatus = frontcacheService.getClusterStatus();
		model.put("edges", clusterStatus.values());

		model.put("hystrixMonitorURLList", frontcacheService.getHystrixMonitorURLList());

		return "realtime";
	}

}
