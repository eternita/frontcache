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

public class CacheStatusActionResponse extends ActionResponse {

	private Map<String, String> cacheStatus;

	public CacheStatusActionResponse() { // for JSON mapper

	}

	public CacheStatusActionResponse(Map<String, String> cacheStatus) {
		setAction("cache status");
		setResponseStatus(RESPONSE_STATUS_OK);
		this.cacheStatus = cacheStatus;
	}

	public Map<String, String> getCacheStatus() {
		return cacheStatus;
	}

	public void setCacheStatus(Map<String, String> cacheStatus) {
		this.cacheStatus = cacheStatus;
	}

}
