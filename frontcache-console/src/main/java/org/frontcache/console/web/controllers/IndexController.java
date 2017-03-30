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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
		List<FrontCacheStatus> edges = new ArrayList<FrontCacheStatus>(clusterStatus.values());
		Collections.sort(edges, new Comparator<FrontCacheStatus>(){
		    public int compare(FrontCacheStatus t1, FrontCacheStatus t2) {
	    		return t1.getName().compareTo(t2.getName());
		    }});
		model.put("edges", edges);

		model.put("hystrixMonitorURLList", frontcacheService.getHystrixMonitorURLList());

		return "realtime";
	}

}
