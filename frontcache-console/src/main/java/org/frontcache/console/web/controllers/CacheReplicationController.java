/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import java.util.Set;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.replication.ReplicationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;


@Controller
@RequestMapping("/cache-replication")
public class CacheReplicationController {

	@Autowired
	private FrontcacheService frontcacheService;


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model) {
		CacheReplicationForm form = new CacheReplicationForm();
		model.addAttribute("cacheReplication", form);

		Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
		model.addAttribute("edgeList", agents);

		model.addAttribute("edges", frontcacheService.getEdgeList());

		ReplicationResult status = frontcacheService.getReplicationStatus();
		model.addAttribute("replicationStatus", status);

		return "cache_replication";
	}


	@RequestMapping(method = RequestMethod.POST, params = "startReplication")
	public String startReplication(Model model, CacheReplicationForm form) {
		model.addAttribute("cacheReplication", form);

		String sourceUrl = form.getSourceEdge();
		String targetUrl = form.getTargetEdge();

		if (sourceUrl != null && targetUrl != null && !sourceUrl.equals(targetUrl)) {
			try {
				frontcacheService.startReplication(sourceUrl, targetUrl);
				model.addAttribute("replicationMessage", "Replication started: " + sourceUrl + " → " + targetUrl);
			} catch (IllegalStateException | IllegalArgumentException e) {
				// IllegalArgumentException includes UnknownEdgeException: an edge this console does not have
				model.addAttribute("replicationError", e.getMessage());
			}
		} else if (sourceUrl != null && sourceUrl.equals(targetUrl)) {
			model.addAttribute("replicationError", "Source and target edge must be different");
		} else {
			model.addAttribute("replicationError", "Please select both source and target edges");
		}

		Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
		model.addAttribute("edgeList", agents);

		model.addAttribute("edges", frontcacheService.getEdgeList());

		ReplicationResult status = frontcacheService.getReplicationStatus();
		model.addAttribute("replicationStatus", status);

		return "cache_replication";
	}


	@RequestMapping(method = RequestMethod.POST, params = "cancelReplication")
	public String cancelReplication(Model model, CacheReplicationForm form) {
		model.addAttribute("cacheReplication", form);

		frontcacheService.cancelReplication();
		model.addAttribute("replicationMessage", "Replication cancellation requested");

		Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
		model.addAttribute("edgeList", agents);

		model.addAttribute("edges", frontcacheService.getEdgeList());

		ReplicationResult status = frontcacheService.getReplicationStatus();
		model.addAttribute("replicationStatus", status);

		return "cache_replication";
	}


	/**
	 * JSON endpoint for live progress polling from the browser.
	 * Returns the current ReplicationResult or null if idle.
	 */
	@RequestMapping(value = "/status", method = RequestMethod.GET, produces = "application/json")
	@ResponseBody
	public ReplicationResult getStatus() {
		return frontcacheService.getReplicationStatus();
	}

}
