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
import org.frontcache.console.model.EdgeConfigFile;
import org.frontcache.io.ConfigFile;

/**
 * Fetches one raw config file from one edge.
 *
 * Replaces {@code BotConfigsCaller}, {@code GuardConfigsCaller}, {@code ResilienceConfigsCaller},
 * {@code DynamicURLsConfigsCaller} and {@code FallbackConfigsCaller}, which were the same class
 * five times with a different method name in the middle. The dispatch lives in
 * {@code FrontCacheClient.getConfigFile}, where the per-file wire compatibility already is.
 */
public class ConfigFileCaller implements Callable<EdgeConfigFile> {

	private final FrontCacheClient fcClient;
	private final ConfigFile configFile;

	public ConfigFileCaller(FrontCacheClient fcClient, ConfigFile configFile) {
		this.fcClient = fcClient;
		this.configFile = configFile;
	}

	@Override
	public EdgeConfigFile call() throws Exception {

		String content = fcClient.getConfigFile(configFile);

		// available=true even for a null content: the edge answered, it just has no such file. The
		// screen says those two things differently, and it should - "no conf/guard-rules.conf" is a
		// normal state and "this node is down" is not.
		return new EdgeConfigFile(fcClient.getName(), fcClient.getFrontCacheURL(), content, true);
	}
}
