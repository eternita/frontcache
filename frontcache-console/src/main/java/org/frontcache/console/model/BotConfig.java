/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.model;

/**
 * Raw ./conf/bots.conf of one edge (comments and empty lines preserved).
 *
 * The parsed counterpart is {@link VisitorTypesConfig}, on the Visitor Types screen. Before 2.9.0
 * this class name meant that one; it now means the file, matching {@link GuardConfig} against
 * {@link GuardRulesConfig}.
 */
public class BotConfig {

	private String name;
	private String config;

	public BotConfig(String name, String config) {
		super();
		this.name = name;
		this.config = config;
	}

	public String getName() {
		return name;
	}

	public String getConfig() {
		return config;
	}

}
