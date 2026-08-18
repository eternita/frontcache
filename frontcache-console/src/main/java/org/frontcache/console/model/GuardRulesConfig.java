/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.model;

import java.util.List;

import org.frontcache.guard.GuardRuleEntry;

/**
 * Guard rules of one edge, in evaluation order.
 *
 * A LIST rather than the per-domain map the other config models use: guard rules are global and
 * their order is what decides which rule wins.
 */
public class GuardRulesConfig {

	private String name;
	private List<GuardRuleEntry> config;

	public GuardRulesConfig(String name, List<GuardRuleEntry> config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public List<GuardRuleEntry> getConfig() {
		return config;
	}

}
