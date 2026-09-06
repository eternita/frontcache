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
 * Raw ./conf/dynamic-urls.conf of one edge (comments and empty lines preserved).
 *
 * Before 2.9.0 this held the flat set of patterns the node had parsed, which is what the screen
 * rendered. It now holds the file, like {@link GuardConfig} and {@link BotConfig} - a pattern list
 * without the comments beside it does not say WHY a URL is never cached, and a commented-out
 * pattern does not show up at all.
 */
public class DynamicURLsConfig {

	private String name;
	private String config;

	public DynamicURLsConfig(String name, String config) {
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
