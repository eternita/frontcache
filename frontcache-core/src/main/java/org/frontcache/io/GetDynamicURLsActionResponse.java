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

public class GetDynamicURLsActionResponse extends ActionResponse {

	private Map<String, Set<String>> dynamicURLs;

	public GetDynamicURLsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Map<String, Set<String>> getDynamicURLs() {
		return dynamicURLs;
	}

	public void setDynamicURLs(Map<String, Set<String>> dynamicURLs) {
		this.dynamicURLs = dynamicURLs;
	}

}
