/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

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

		// The edge list, not a fan-out: this page renders the same live block as the others, so the
		// dot and the item count are filled by the poll rather than by a round trip per edge before
		// the page can paint. Sorting by name needs no network.
		List<FrontCacheStatus> edges = frontcacheService.getEdgeList();
		Collections.sort(edges, new Comparator<FrontCacheStatus>(){
		    public int compare(FrontCacheStatus t1, FrontCacheStatus t2) {
	    		return t1.getName().compareTo(t2.getName());
		    }});
		model.put("edges", edges);

		return "edges";
	}

	/**
	 * POST, not GET, as of 2.9.0: this changes console state, and a GET that does so is reachable
	 * from any page the operator has open - the browser attaches the console's Basic credentials to
	 * a cross-site request on its own, and they are not cookies, so SameSite does not stop it.
	 * SameOriginFilter checks the origin of the POST; the method change is what brings it under that
	 * check at all. See docs/archive/basic-auth-proposal.md section 8.1.
	 */
	@RequestMapping(value = "/add-edge", method = RequestMethod.POST)
	public String addEdge(HttpServletRequest request) {
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
	@RequestMapping(value = "/remove-edge", method = RequestMethod.POST)
	public String removeEdge(HttpServletRequest request) {
		String edge = request.getParameter("edge");
		frontcacheService.removeEdge(edge);
		return "redirect:edges";
	}

}
