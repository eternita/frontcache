/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.frontcache.wrapper.HttpResponseWrapperImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FC_ThroughCache_WebFilter extends FcCommand<WebResponse> {


	String url = "open-circuit-default-key"; // when circuit is open - the value is not overriden during run() call
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_WebFilter.class);

    public FC_ThroughCache_WebFilter(RequestContext context) {

        super(Setter
                .withGroupKey(context.getDomain())
                .andCommandKey("Cache-Origin-filter")
				.andThreadPoolKey("OriginHitsPool")
        		);

        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpServletRequest httpRequest = context.getRequest();
		url = FCUtils.getRequestURL(httpRequest);
		HttpServletResponse httpResponse = context.getResponse();
		FilterChain chain = context.getFilterChain();

		FrontCacheHttpResponseWrapper wrappedResponse = new HttpResponseWrapperImpl(httpResponse);

		try {
			chain.doFilter(httpRequest, wrappedResponse); // run request to origin

			WebResponse webResponse = FCUtils.httpResponse2WebComponent(url, wrappedResponse, context);
			return webResponse;

		} catch (IOException | ServletException e) {
			logger.error("Can't read from " + url, e);
			throw new FrontCacheException("FilterChain exception", e);
		}

    }

    @Override
    protected WebResponse getFallback() {
		context.setHystrixFallback(FallbackLogger.failureType(getExecutionEvents()));

		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();

		logger.error("FC_ThroughCache_WebFilter - ERROR FOR - " + url + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context, getFailedExecutionException());

		WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(this.getClass().getName(), url);
		return webResponse;
    }


}