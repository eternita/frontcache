/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import jakarta.servlet.http.HttpServletRequest;

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

		model.put("hystrixMonitorURLList", frontcacheService.getHystrixMonitorURLList());

		return "realtime";
	}

}
