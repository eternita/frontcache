/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import java.util.Map;
import java.util.Set;

import org.frontcache.resilience.fr.FallbackConfigEntry;

public class GetFallbackConfigActionResponse extends ActionResponse {

	private Map <String, Set<FallbackConfigEntry>> fallbackConfigs;

	public GetFallbackConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Map<String, Set<FallbackConfigEntry>> getFallbackConfigs() {
		return fallbackConfigs;
	}

	public void setFallbackConfigs(Map<String, Set<FallbackConfigEntry>> fallbackConfigs) {
		this.fallbackConfigs = fallbackConfigs;
	}

}
