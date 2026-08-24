/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import java.util.Map;
import java.util.Set;

/**
 * Bot user-agent keywords from ./conf/bots.conf.
 *
 * A flat set as of 2.7. Before that it was a {@code Map<domain, Set<String>>} - a shape that outlived
 * its reason: one node serves one site, so the map always held exactly one entry, and the console's
 * JSPs had to switch on the literal {@code "default-domain"} to render it.
 *
 * Both shapes go out on the wire, and both are accepted coming in. See
 * {@link LegacyDomainKeyedField} for why.
 */
public class GetBotsActionResponse extends ActionResponse {

	private Set<String> botKeywords;

	public GetBotsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Set<String> getBotKeywords() {
		return botKeywords;
	}

	public void setBotKeywords(Set<String> botKeywords) {
		this.botKeywords = botKeywords;
	}

	/**
	 * @deprecated pre-2.7 shape of {@link #getBotKeywords()} - a single-entry map keyed by domain.
	 */
	@Deprecated
	public Map<String, Set<String>> getBots() {
		return LegacyDomainKeyedField.wrap(botKeywords);
	}

	/** @deprecated pre-2.7 shape of {@link #setBotKeywords(Set)} */
	@Deprecated
	public void setBots(Map<String, Set<String>> bots) {
		if (null == botKeywords)
			botKeywords = LegacyDomainKeyedField.flatten(bots);
	}

}
