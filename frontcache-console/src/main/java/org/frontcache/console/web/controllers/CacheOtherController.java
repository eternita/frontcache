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
@RequestMapping("/cache-other")
public class CacheOtherController {

	@Autowired
	private FrontcacheService frontcacheService;


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model) {
		CacheOtherForm cacheOtherForm = new CacheOtherForm();
		model.addAttribute("cacheOther", cacheOtherForm);

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}


	@RequestMapping(method = RequestMethod.POST, params = "dumpKeys")
	public String dumpKeys(Model model, CacheOtherForm cacheOtherForm) {
		model.addAttribute("cacheOther", cacheOtherForm);

		String dumpKeysResult = frontcacheService.dumpKeys(cacheOtherForm.getEdge());
		model.addAttribute("dumpKeysResult", dumpKeysResult);

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}


	@RequestMapping(method = RequestMethod.POST, params = "purge")
	public String purge(Model model, CacheOtherForm cacheOtherForm) {
		model.addAttribute("cacheOther", cacheOtherForm);

		frontcacheService.purgeEdge(cacheOtherForm.getEdge());
		model.addAttribute("purgeResult", "Purge of expired entries started on " + cacheOtherForm.getEdge());

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}


	@RequestMapping(method = RequestMethod.POST, params = "backupIndex")
	public String backupIndex(Model model, CacheOtherForm cacheOtherForm) {
		model.addAttribute("cacheOther", cacheOtherForm);

		String backupResult = frontcacheService.backupEdge(cacheOtherForm.getEdge());
		model.addAttribute("backupResult", backupResult + " on " + cacheOtherForm.getEdge());

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);

		return "cache_other";
	}
}
