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
 * Response for the {@code put-to-cache} IO action.
 * <p>
 * Returned by {@link org.frontcache.io.FrontCacheIOServlet} when a
 * {@link org.frontcache.core.WebResponse} is stored into the local cache
 * (used by the index-replication feature to push entries from a source edge
 * to a target edge).
 */
public class PutToCacheActionResponse extends ActionResponse {

	private String key;
	private String domain;

	/** No-arg constructor for Jackson deserialization. */
	public PutToCacheActionResponse() {
	}

	public PutToCacheActionResponse(String key, String domain) {
		setAction("put to cache");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.key = key;
		this.domain = domain;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getDomain() {
		return domain;
	}

	public void setDomain(String domain) {
		this.domain = domain;
	}

}
