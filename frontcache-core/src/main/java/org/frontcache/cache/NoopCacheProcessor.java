/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache;

import org.frontcache.core.WebResponse;

import java.util.List;

public class NoopCacheProcessor extends CacheProcessorBase implements CacheProcessor {


	@Override
	public WebResponse getFromCacheImpl(String url) {
		return null;
	}

	@Override
	public void putToCacheImpl(String url, WebResponse component) {

	}

	@Override
	public void removeFromCache(String filter) {

	}

	@Override
	public void removeFromCacheAll() {

	}

	@Override
	public List<String> getCachedKeys() {
		return List.of();
	}

	@Override
	public void patch() {

	}

	@Override
	public void purge() {

	}
}



