/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.console.model.DashboardStream;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.plugin.DashboardStreamContributor;
import org.frontcache.console.service.FrontcacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class IndexController {

	private static final Logger logger = LoggerFactory.getLogger(IndexController.class);

	@Autowired
	private FrontcacheService frontcacheService;

	/** Console plugins' additions to the realtime page's edge list - none on a Community console. */
	@Autowired(required = false)
	private List<DashboardStreamContributor> streamContributors = List.of();

	@RequestMapping(value = {"/", "/index"}, method = RequestMethod.GET)
	public String index(HttpServletRequest request) {

		if (frontcacheService.getEdgesAmount() > 0)
			return "redirect:realtime";
		else {

			// check if it's development / test mode
			// (FC edge and console running locally on the same host)
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

		// names and URLs only - no fan-out. The numbers, and the online dot itself, arrive from
		// /edges-status.json a moment after the page paints; see inc/edges_status_stacked.jsp.
		model.put("edges", frontcacheService.getEdgeList());

		// a console plugin fills in what the core does not know - the Pro cluster membership
		List<DashboardStream> streams = frontcacheService.getDashboardStreamList();
		for (DashboardStreamContributor contributor : streamContributors)
		{
			try {
				contributor.contribute(streams);
			} catch (RuntimeException e) {
				logger.warn("A dashboard stream contributor failed - the realtime page renders without it", e);
			}
		}
		model.put("dashboardStreams", frontcacheService.toJson(streams));

		return "realtime";
	}

	/**
	 * The "Edges status" numbers, for the page to poll - see fc-edges-status.js.
	 *
	 * Raw numbers, formatted in the browser, with -1 meaning "the node did not report this" rather
	 * than zero. Served from a short-lived fan-out cache so that the number of open dashboards does
	 * not multiply the load on the fleet.
	 */
	@RequestMapping(value = "/edges-status.json", method = RequestMethod.GET, produces = "application/json")
	@ResponseBody
	public List<FrontCacheStatus> edgesStatus() {
		return frontcacheService.getClusterStatusListPolled();
	}

}
