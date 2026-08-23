/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * All GET requests which flows through cache (cached & not)
 *
 */
public class FC_ThroughCache extends FcCommand<WebResponse> {

	private final String originUrlStr;
	private final CacheProcessorBase cacheProcessorBase;
	private final RequestContext context;

	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache.class);

    public FC_ThroughCache(CacheProcessorBase cacheProcessorBase, String originUrlStr, RequestContext context) {
        // group by domain (matches FC_Total / FC_BypassCache / RequestLogger).
        // context can be null for admin (FrontCacheIOServlet) lookups -> read the domain from config
        // directly. This used to read front-cache.origin-host, which put admin lookups in a group of
        // their own named after the origin - not what the comment said, and a bogus dashboard entry.
        super(Setter
                .withGroupKey(null != context
                                ? context.getDomain()
                                : FCConfig.getProperty("front-cache.default-domain", FCConfig.DEFAULT_DOMAIN))
                .andCommandKey("Cache-Hits")
        		);

        this.originUrlStr = originUrlStr;
        this.cacheProcessorBase = cacheProcessorBase;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
    	return cacheProcessorBase.getFromCacheImpl(originUrlStr);
    }

    @Override
    protected WebResponse getFallback() {

		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();

		logger.error("FC_ThroughCache - ERROR FOR - " + originUrlStr + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context, getFailedExecutionException());

		return null;
    }


}