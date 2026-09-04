/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include.combine;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One member's answer inside a {@link CombineEnvelope}. See docs/archive/combine-reduce-proposal.md
 * section 6.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is not decoration: it is what lets a later
 * protocol version add a field without a flag day between an edge and an origin that upgrade
 * independently - the same rule the console wire format runs on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CombinePart {

	/**
	 * Optional echo of the member's decoded query string.
	 *
	 * Optional, and verified when present (proposal section 18, decision 1). It costs the origin one
	 * field and catches the entire class of "the batch handler reordered its results" bugs, which
	 * are otherwise invisible: the page renders, with every cell showing another cell's content.
	 */
	private String q;

	/** HTTP-ish status for this member. Absent means 200. A non-2xx part is never cached. */
	private int status = 200;

	/**
	 * The member's response headers, read by the edge exactly as a single include's response headers
	 * are - so {@code x-frontcache-component-maxage}, {@code -tags}, {@code -refresh},
	 * {@code -cache-level} and {@code Content-Type} mean what they always mean. No maxage means not
	 * cacheable, same default as a single include with no maxage.
	 */
	private Map<String, List<String>> headers;

	/** The fragment. A JSON string; the envelope is UTF-8. */
	private String body;

	public CombinePart() {
	}

	public CombinePart(String q, int status, Map<String, List<String>> headers, String body) {
		this.q = q;
		this.status = status;
		this.headers = headers;
		this.body = body;
	}

	public String getQ() {
		return q;
	}

	public void setQ(String q) {
		this.q = q;
	}

	public int getStatus() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, List<String>> headers) {
		this.headers = headers;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}
}
