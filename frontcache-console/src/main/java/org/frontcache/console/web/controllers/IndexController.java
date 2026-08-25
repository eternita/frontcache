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

import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

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

		model.put("edges", frontcacheService.getClusterStatusList());

		model.put("dashboardStreams", frontcacheService.getDashboardStreams());

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
