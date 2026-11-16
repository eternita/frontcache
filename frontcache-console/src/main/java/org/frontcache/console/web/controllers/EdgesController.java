/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.console.web.controllers;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.auth.ConsoleAudit;
import org.frontcache.console.auth.ConsolePermissions;
import org.frontcache.console.auth.ConsolePermit;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.auth.ConsoleSecurity;
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

	@Autowired
	private ConsoleSecurity consoleSecurity;

	@RequestMapping(value = "/edges", method = RequestMethod.GET)
	public String domainRealtimeMonitor(ModelMap model, EdgeScope scope) {

		// The edge list, not a fan-out: this page renders the same live block as the others, so the
		// dot and the item count are filled by the poll rather than by a round trip per edge before
		// the page can paint. Sorting by name needs no network.
		List<FrontCacheStatus> edges = frontcacheService.getEdgeList(scope);
		Collections.sort(edges, new Comparator<FrontCacheStatus>(){
		    public int compare(FrontCacheStatus t1, FrontCacheStatus t2) {
	    		return t1.getName().compareTo(t2.getName());
		    }});
		model.put("edges", edges);
		model.put("edgeEditingEnabled", consoleSecurity.edgeEditing());
		// the edges entries (configured URL -> label): shown, never removable here - they are the config's
		model.put("keyedEdges", frontcacheService.keyedEdgeLabels());

		return "edges";
	}

	/**
	 * POST, not GET, as of 2.9.0: this changes console state, and a GET that does so is reachable
	 * from any page the operator has open - the browser attaches the console's Basic credentials to
	 * a cross-site request on its own, and they are not cookies, so SameSite does not stop it.
	 * SameOriginFilter checks the origin of the POST; the method change is what brings it under that
	 * check at all.
	 */
	@ConsolePermit(value = ConsolePermissions.EDGES_WRITE, edgeParameter = "")
	@ConsoleAudit(value = "edges.add", parameters = "edges")
	@RequestMapping(value = "/add-edge", method = RequestMethod.POST)
	public String addEdge(HttpServletRequest request, HttpServletResponse response, EdgeScope scope) throws IOException {
		if (!consoleSecurity.edgeEditing())
			return refuse(response);

		String edges = request.getParameter("edges");
		if (null == edges)
			return "redirect:edges";

		String[] arr = edges.split(" ");
		for (String edge : arr)
		{
			if (edge.length() > 0)
				frontcacheService.addEdge(edge);
		}

		return "redirect:edges";
	}

	/** POST for the same reason as {@link #addEdge} - it removes an edge from the console. */
	@ConsolePermit(ConsolePermissions.EDGES_WRITE)
	@ConsoleAudit("edges.remove")
	@RequestMapping(value = "/remove-edge", method = RequestMethod.POST)
	public String removeEdge(HttpServletRequest request, HttpServletResponse response, EdgeScope scope) throws IOException {
		if (!consoleSecurity.edgeEditing())
			return refuse(response);

		String edge = request.getParameter("edge");
		frontcacheService.removeEdge(edge);
		return "redirect:edges";
	}

	/** {@code edge-editing = false}: the list is decided where the console's config is written. */
	private String refuse(HttpServletResponse response) throws IOException {
		response.sendError(HttpServletResponse.SC_FORBIDDEN, "edge editing is disabled on this console");
		return null;
	}

}
