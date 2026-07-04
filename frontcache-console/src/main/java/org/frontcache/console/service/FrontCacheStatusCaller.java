/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import java.util.Map;
import java.util.concurrent.Callable;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.FrontCacheStatus;

public class FrontCacheStatusCaller implements Callable<FrontCacheStatus> {

	private FrontCacheClient fcClient;

	public FrontCacheStatusCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public FrontCacheStatus call() throws Exception {

    	FrontCacheStatus fcStatus = new FrontCacheStatus();
		fcStatus.setName(fcClient.getName());
		long cachedAmount = -1;
		boolean available = true;
		try
		{
			Map<String, String> cacheState = fcClient.getCacheState();
			if (null != cacheState)
			{
				String cacheAmountStr = cacheState.get(CacheProcessor.CACHED_ENTRIES);

				if (null != cacheAmountStr)
				{
					try
					{
						cachedAmount = Long.parseLong(cacheAmountStr);
					} catch (Exception e) {e.printStackTrace();}
				}
			} else {
				available = false;
			}
		} catch (Throwable ex) {
			available = false;
			ex.printStackTrace();
		}

		fcStatus.setAvailable(available);
		fcStatus.setCachedAmount(cachedAmount);
		fcStatus.setUrl(fcClient.getFrontCacheURL());

        return fcStatus;
    }
}
