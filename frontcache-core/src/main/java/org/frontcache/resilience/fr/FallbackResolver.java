/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience.fr;

import java.util.Map;
import java.util.Set;

import org.apache.http.client.HttpClient;
import org.frontcache.core.WebResponse;

public interface FallbackResolver {

	public void init(HttpClient client);

	public WebResponse getFallback(String fallbackSource, String urlStr);

	public Map <String, Set<FallbackConfigEntry>> getFallbackConfigs();
}
