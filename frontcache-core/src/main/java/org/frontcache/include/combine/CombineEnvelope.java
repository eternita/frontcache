/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include.combine;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The combined response: one part per requested member, in the order the members were sent.
 * See docs/combine-reduce-proposal.md section 6.
 *
 * <pre>
 * {"v":1,"parts":[{"q":"id=1","status":200,"headers":{...},"body":"..."}, ...]}
 * </pre>
 *
 * {@code parts} is positional against {@code fc-part-{i}}: {@code parts.size()} must equal the
 * requested count and {@code parts.get(i)} must answer {@code fc-part-i}. Keying by query string
 * instead was rejected - it would make the edge re-normalise query strings to match them up, and a
 * normalisation mismatch is a silent wrong-content bug rather than a loud one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CombineEnvelope {

	/**
	 * Shared, and safe to share: ObjectMapper is documented thread-safe once configured, and
	 * constructing one per batch is the single most expensive thing about parsing a small envelope.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private int v = CombineProtocol.VERSION;

	private List<CombinePart> parts = new ArrayList<CombinePart>();

	public int getV() {
		return v;
	}

	public void setV(int v) {
		this.v = v;
	}

	public List<CombinePart> getParts() {
		return parts;
	}

	public void setParts(List<CombinePart> parts) {
		this.parts = parts;
	}

	public CombineEnvelope add(CombinePart part) {
		this.parts.add(part);
		return this;
	}

	public String toJson() throws java.io.IOException {
		return MAPPER.writeValueAsString(this);
	}

	public static CombineEnvelope fromJson(byte[] json) throws java.io.IOException {
		return MAPPER.readValue(json, CombineEnvelope.class);
	}

	public static CombineEnvelope fromJson(String json) throws java.io.IOException {
		return MAPPER.readValue(json, CombineEnvelope.class);
	}
}
