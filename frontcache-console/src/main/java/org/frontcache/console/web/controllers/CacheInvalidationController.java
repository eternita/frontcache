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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
@RequestMapping("/cache-invalidation")
public class CacheInvalidationController {

	@Autowired
	private FrontcacheService frontcacheService;


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model) {
		CacheInvalidationForm cacheInvalidationForm = new CacheInvalidationForm();
		model.addAttribute("cacheInvalidation", cacheInvalidationForm);

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		model.addAttribute("edges", frontcacheService.getEdgeList());

		return "cache_invalidation";
	}


	@RequestMapping(method = RequestMethod.POST, params = "invalidate")
	public String submitForm(Model model, CacheInvalidationForm cacheInvalidationForm) {
		model.addAttribute("cacheInvalidation", cacheInvalidationForm);

		frontcacheService.invalidateEdge(cacheInvalidationForm.getEdge(), cacheInvalidationForm.getFilter());

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		model.addAttribute("edges", frontcacheService.getEdgeList());

		return "cache_invalidation";
	}


	@RequestMapping(method = RequestMethod.POST, params = "purge")
	public String purge(Model model, CacheInvalidationForm cacheInvalidationForm) {
		model.addAttribute("cacheInvalidation", cacheInvalidationForm);

		frontcacheService.purgeEdge(cacheInvalidationForm.getEdge());
		model.addAttribute("purgeResult", "Purge of expired entries started on " + cacheInvalidationForm.getEdge());

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		model.addAttribute("edges", frontcacheService.getEdgeList());

		return "cache_invalidation";
	}
}
