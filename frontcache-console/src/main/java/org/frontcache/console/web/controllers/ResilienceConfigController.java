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


@Controller
public class ResilienceConfigController {

	@Autowired
	private FrontcacheService frontcacheService;

    /**
     * Both paths are mapped: /hystrix-configs is what this page was called before 2.7, and it is a URL
     * a person can have bookmarked or linked to, so it keeps resolving rather than 404ing.
     */
    @RequestMapping(value = { "/resilience-configs", "/hystrix-configs" }, method = RequestMethod.GET)
    public String getResilienceConfigs(ModelMap model) {

    	Map<String, String> resilienceConfigs = frontcacheService.getResilienceConfigs();
		model.put("resilienceConfigs", resilienceConfigs);

        return "resilience_configs";
    }


}
