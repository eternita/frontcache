/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

/**
 * The raw ./conf/fallbacks.conf of one node, for the console's Fallback Config screen.
 *
 * <b>Not to be confused with {@link GetFallbackConfigActionResponse}</b>, one letter away, which
 * carries the parsed {@code fallbackEntries} and answers {@code get-fallback-configs}. This one
 * answers {@code get-fallbacks-config} and carries the file. The names are close because both are
 * named after the same thing from different angles - the plural in the older one counts the
 * ENTRIES, the plural here is part of the file name, {@code fallbacks.conf}.
 *
 * Same split as guard's, bots' and dynamic-urls': the parsed shape says what is in effect, the raw
 * file says what the operator wrote, including the comments and any line the parser skipped.
 */
public class GetFallbacksConfigActionResponse extends ActionResponse {

	// raw content of ./conf/fallbacks.conf (comments and empty lines preserved)
	private String fallbacksConfig;

	public GetFallbacksConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getFallbacksConfig() {
		return fallbacksConfig;
	}

	public void setFallbacksConfig(String fallbacksConfig) {
		this.fallbacksConfig = fallbacksConfig;
	}

}
