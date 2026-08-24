/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.model;

import java.util.Set;

/**
 * One edge's never-cached URL patterns. A flat set as of 2.7 - see {@link BotConfig}.
 */
public class DynamicURLsConfig {

	private String name;
	private Set<String> config;

	public DynamicURLsConfig(String name, Set<String> config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public Set<String> getConfig() {
		return config;
	}

}
