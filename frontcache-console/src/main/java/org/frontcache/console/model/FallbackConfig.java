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

import org.frontcache.resilience.fr.FallbackConfigEntry;

/**
 * One edge's fallback entries. A flat set as of 2.7 - see {@link VisitorTypesConfig}.
 */
public class FallbackConfig {

	private String name;
	private Set<FallbackConfigEntry> config;

	public FallbackConfig(String name, Set<FallbackConfigEntry> config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public Set<FallbackConfigEntry> getConfig() {
		return config;
	}

}
