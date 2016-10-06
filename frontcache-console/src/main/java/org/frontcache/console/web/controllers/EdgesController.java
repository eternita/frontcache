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
public class EdgesController {

	@Autowired
	private FrontcacheService frontcacheService;

	@RequestMapping(value = "/edges", method = RequestMethod.GET)
	public String domainRealtimeMonitor(ModelMap model) {

		
		
		Map<String, FrontCacheStatus> clusterStatus = frontcacheService.getClusterStatus();
		model.put("edges", clusterStatus.values());


		return "edges";
	}

	@RequestMapping(value = "/add-edge", method = RequestMethod.GET)
	public String addEdge(HttpServletRequest request) {
		String edges = request.getParameter("edges");
		String[] arr = edges.split(" ");
		for (String edge : arr)
		{
			if (edge.length() > 0)
				frontcacheService.addEdge(edge);
		}
		
		return "redirect:edges";
	}
	
	@RequestMapping(value = "/remove-edge", method = RequestMethod.GET)
	public String removeEdge(HttpServletRequest request) {
		String edge = request.getParameter("edge");
		frontcacheService.removeEdge(edge);
		return "redirect:edges";
	}
	
}
