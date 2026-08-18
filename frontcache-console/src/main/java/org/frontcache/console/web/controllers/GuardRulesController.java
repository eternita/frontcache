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
import java.util.Map;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.guard.GuardRuleEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
public class GuardRulesController {

	@Autowired
	private FrontcacheService frontcacheService;

    @RequestMapping(value = "/guard-rules", method = RequestMethod.GET)
    public String getGuardRules(ModelMap model) {

    	Map<String, List<GuardRuleEntry>> guardRules = frontcacheService.getGuardRules();
		model.put("guardRules", guardRules);

        return "guard_rules";
    }


}
