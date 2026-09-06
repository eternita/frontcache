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
 * Configs / Dynamic URLs Config: the raw conf/dynamic-urls.conf of each edge, shown as it sits on
 * disk - the same treatment as the resilience, guard and bots files.
 */
@Controller
public class DynamicURLConfigController {

	@Autowired
	private FrontcacheService frontcacheService;

    @RequestMapping(value = "/dynamic-urls-configs", method = RequestMethod.GET)
    public String getDynamicURLConfigs(ModelMap model) {

    	Map<String, String> dynamicURLsConfigs = frontcacheService.getDynamicURLsConfigs();
		model.put("dynamicURLsConfigs", dynamicURLsConfigs);

        return "dynamic_urls_configs";
    }


}
