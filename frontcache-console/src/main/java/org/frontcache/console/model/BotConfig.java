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
 * One edge's bot user-agent keywords.
 *
 * A flat set as of 2.7: the management API used to return these keyed by domain, which always held a
 * single entry once one node served one site. See docs/multi-domain-retirement-proposal.md section 8.
 */
public class BotConfig {

	private String name;
	private Set<String> config;

	public BotConfig(String name, Set<String> config) {
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
