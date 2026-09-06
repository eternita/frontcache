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
 * Configs / Bot Configs: the raw conf/bots.conf of each edge, shown as it sits on disk.
 *
 * The parsed view - the rules in evaluation order, with hit counts - is the Visitor Types screen
 * ({@link VisitorTypesController}), which is what this URL served through 2.10.
 */
@Controller
public class BotConfigController {

	@Autowired
	private FrontcacheService frontcacheService;

    @RequestMapping(value = "/bot-configs", method = RequestMethod.GET)
    public String getBotConfigs(ModelMap model) {

    	Map<String, String> botConfigs = frontcacheService.getBotConfigs();
		model.put("botConfigs", botConfigs);

        return "bot_configs";
    }


}
