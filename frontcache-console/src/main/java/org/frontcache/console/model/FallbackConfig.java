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
 * Raw ./conf/fallbacks.conf of one edge (comments and empty lines preserved).
 *
 * Through 2.10 this held the flat set of parsed entries the screen rendered as a table. It now holds
 * the file, like {@link GuardConfig}, {@link BotConfig} and {@link DynamicURLsConfig} - the table
 * showed the three fields of an entry and nothing else, so the notes explaining what each fallback
 * is for, and any entry commented out, were invisible.
 */
public class FallbackConfig {

	private String name;
	private String config;

	public FallbackConfig(String name, String config) {
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
