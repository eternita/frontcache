/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.message.BasicHttpRequest;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FC_BypassCache extends FcCommand<Object> {

	private final HttpClient client;
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_BypassCache.class);

    public FC_BypassCache(HttpClient client, RequestContext context) {

        super(Setter
                .withGroupKey(context.getDomain())
                .andCommandKey("Origin-Hits")
				.andThreadPoolKey("OriginHitsPool")
        		);

        this.client = client;
        this.context = context;
    }

    @Override
    protected Object run() throws Exception {

    	try {
    		forwardToOrigin();
    	} finally {
    		// Signal that this thread is done with the origin response. In the normal flow the request
    		// thread is still streaming the body, so this is a no-op and that thread closes. When we
    		// lost the race to a command TIMEOUT it has already finished (through the fallback) and
    		// left the response for us - so the close happens here instead.
    		// Must be in a finally: on an aborted/failed call there is nothing to close, but the
    		// request thread must not be left waiting on a party that never arrives.
    		context.releaseHttpClientResponse();
    	}

    	return null;
    }

    @Override
    protected Object getFallback() {

		try {
			HttpServletRequest httpRequest = context.getRequest();
			String url = FCUtils.getRequestURL(httpRequest);

			context.setFallbackUsed(FallbackLogger.failureType(getExecutionEvents()));

			String failedExceptionMessage = "";
			if (null != getFailedExecutionException())
				failedExceptionMessage += getFailedExecutionException().getMessage();

			logger.error("FC - ORIGIN ERROR - " + url + " " + failedExceptionMessage + ", Events " + getExecutionEvents(), getFailedExecutionException());

			WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(this.getClass().getName(), url);

			// Park the fallback for the request thread to write - don't write it here. This runs on a
			// command worker thread while the request thread is about to write the response itself, and two
			// writers on one servlet output stream produced a mangled response (see
			// RequestContext.setFallbackWebResponse). FrontCacheEngine serves whatever is parked here.
			context.setFallbackWebResponse(webResponse);
		} catch (Exception e) {
			e.printStackTrace();
		}
        return null;
    }


	private void forwardToOrigin() throws IOException, ServletException
	{
		HttpServletRequest request = context.getRequest();

		if (context.isFilterMode())
		{
			HttpServletResponse response = context.getResponse();
			FilterChain chain = context.getFilterChain();
			chain.doFilter(request, response);
		} else {

			// stand alone mode

			Map<String, List<String>> headers = FCUtils.buildRequestHeaders(request);
			headers.put(FCHeaders.X_FRONTCACHE_CLIENT_IP, Arrays.asList(new String[]{context.getClientIP()}));

			String verb = request.getMethod();
			InputStream requestEntity = getRequestBody(request);
			String uri = context.getRequestURI();

			try {
				HttpResponse response = forward(client, verb, uri, request, headers, requestEntity);

				// response 2 context
				setResponse(response);

			}
			catch (Exception ex) {
				ex.printStackTrace();
				context.set("error.status_code", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				context.set("error.exception", ex);

				context.setFallbackUsed();
				logger.error("FC - ORIGIN ERROR - " + uri);

			}
		}

		return;
	}


	private void setResponse(HttpResponse response) throws IOException {

		context.setHttpClientResponse((CloseableHttpResponse) response);

		setResponse(response.getStatusLine().getStatusCode(),
				response.getEntity() == null ? null : response.getEntity().getContent(),
				FCUtils.revertHeaders(response.getAllHeaders()));
	}


	private void setResponse(int status, InputStream entity, Map<String, List<String>> headers) throws IOException {

		context.setResponseStatusCode(status);

		if (entity != null) {
			context.setResponseDataStream(entity);
		}

		for (String key : headers.keySet()) {
			for (String value : headers.get(key)) {
				context.addOriginResponseHeader(key, value);
			}
		}

	}


	private InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		try {
			requestEntity = request.getInputStream();
		}
		catch (IOException ex) {
			// no requestBody is ok.
		}
		return requestEntity;
	}

	/**
	 * forward all kind of requests (GET, POST, PUT, ...)
	 *
	 * @param httpclient
	 * @param verb
	 * @param uri
	 * @param request
	 * @param headers
	 * @param params
	 * @param requestEntity
	 * @return
	 * @throws Exception
	 */
	// package-private (not private) so unit tests in this package can drive the origin call directly
	HttpResponse forward(HttpClient httpclient, String verb, String uri, HttpServletRequest request,
			Map<String, List<String>> headers, InputStream requestEntity)
					throws Exception {

		URL host = context.getOriginURL();
		HttpHost httpHost = FCUtils.getHttpHost(host);
		uri = (host.getPath() + uri).replaceAll("/{2,}", "/");

		HttpRequest httpRequest;
		switch (verb.toUpperCase()) {
		case "POST":
			HttpPost httpPost = new HttpPost(uri + context.getRequestQueryString());
			httpRequest = httpPost;
			httpPost.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		case "PUT":
			HttpPut httpPut = new HttpPut(uri + context.getRequestQueryString());
			httpRequest = httpPut;
			httpPut.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		case "PATCH":
			HttpPatch httpPatch = new HttpPatch(uri + context.getRequestQueryString());
			httpRequest = httpPatch;
			httpPatch.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		default:
			httpRequest = new BasicHttpRequest(verb, uri + context.getRequestQueryString());
		}


		try {
			httpRequest.setHeaders(FCUtils.convertHeaders(headers));
			// Always advertise gzip (and only gzip) to the origin. Our HttpClient transparently
			// decodes gzip, so Frontcache always receives plaintext it can parse/cache and never an
			// encoding it cannot handle (e.g. br/zstd). buildRequestHeaders() normalizes this too;
			// setting it here makes the origin call self-contained, independent of the inbound headers.
			httpRequest.setHeader(FCHeaders.ACCEPT_ENCODING, "gzip");
			HttpResponse originResponse = httpclient.execute(httpHost, httpRequest);
			return originResponse;
		} finally {
			// When HttpClient instance is no longer needed,
			// shut down the connection manager to ensure
			// immediate deallocation of all system resources
			// httpclient.getConnectionManager().shutdown();
		}
	}

}