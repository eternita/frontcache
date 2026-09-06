/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import java.util.concurrent.Callable;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.BotConfig;
import org.frontcache.io.GetBotsActionResponse;

public class BotConfigsCaller implements Callable<BotConfig> {

	private FrontCacheClient fcClient;

	public BotConfigsCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public BotConfig call() throws Exception {

		// One call, both shapes: an edge older than 2.10 answers with keywords and no rules.
		GetBotsActionResponse response = fcClient.getBotsActionResponse();
		if (null == response)
			return new BotConfig(fcClient.getName(), null, null);

		return new BotConfig(fcClient.getName(), response.getBotKeywords(), response.getBotRules());
    }
}
