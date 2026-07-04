/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.model;

import java.util.Map;
import java.util.Set;

public class DynamicURLsConfig {

	private String name;
	private Map<String, Set<String>> config;

	public DynamicURLsConfig(String name, Map<String, Set<String>> config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public Map<String, Set<String>> getConfig() {
		return config;
	}

}
