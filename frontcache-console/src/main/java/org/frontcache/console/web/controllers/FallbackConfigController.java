/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import java.util.Map;

import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


/**
 * Configs / Fallback Config: the raw conf/fallbacks.conf of each edge, shown as it sits on disk -
 * the same treatment as the resilience, guard, bots and dynamic-urls files.
 */
@Controller
public class FallbackConfigController {

	@Autowired
	private FrontcacheService frontcacheService;

    @RequestMapping(value = "/fallbacks", method = RequestMethod.GET)
    public String fallbacks(ModelMap model) {

    	Map<String, String> fallbackConfigs = frontcacheService.getFallbackConfigs();
		model.put("fallbackConfigs", fallbackConfigs);

        return "fallback_settings";
    }


}
