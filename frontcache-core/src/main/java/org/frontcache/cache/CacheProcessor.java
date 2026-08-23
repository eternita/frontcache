/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

public interface CacheProcessor {

	public final static String CACHED_ENTRIES = "cached-entries";

	public final static long NO_CACHE = 0;

	public final static long CACHE_FOREVER = -1;

	public void init(Properties properties);

	public void destroy();

	public void putToCache(String url, WebResponse component);

	public WebResponse getFromCache(String url, RequestContext context);

	public void removeFromCache(String filter);

	/**
	 * Removes every entry from this node.
	 *
	 * There used to be a domain-scoped flavour alongside this one. It only dropped entries stamped
	 * with the caller's domain, and replicated entries carry the domain of the edge they came from -
	 * so an "invalidate all" at the target edge walked straight past them and they stayed served.
	 * With one site per node there is one flavour, and it means what it says.
	 */
	public void removeFromCacheAll();

	public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException;

	public Map<String, String> getCacheStatus();

	public List<String> getCachedKeys();

	public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context);

	public void patch();

	public void purge();

	/**
	 * Backs up the on-disk index (L2) to a sibling directory at the edge.
	 * Cache processors without a persistent index (in-memory, no-cache) leave this a no-op.
	 *
	 * @return the name of the created backup directory, or {@code null} if the processor has nothing to back up
	 */
	public default String backup() {
		return null;
	}
}
