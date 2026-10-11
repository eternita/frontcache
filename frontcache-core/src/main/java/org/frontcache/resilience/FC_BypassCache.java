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
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.frontcache.FrontCacheEngine;
import org.frontcache.core.EdgeToken;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FC_BypassCache extends FcCommand<Object> {

	// Compiled once. This runs on EVERY bypassed (non-cacheable) request, and String.replaceAll()
	// compiles its pattern each time it is called.
	private static final Pattern DUPLICATE_SLASHES = Pattern.compile("/{2,}");

	private final HttpClient client;
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_BypassCache.class);

    public FC_BypassCache(HttpClient client, RequestContext context) {

        super(Setter
                .withGroupKey(context.getDomain())
                .andCommandKey(FcCommandKeys.CACHE_BYPASS)
				.andThreadPoolKey(FcCommandKeys.ORIGIN_POOL)
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

			// Record the status the app just set. Nothing else on this branch does - the standalone
			// branch below gets it from setResponse(), and RequestContext.getResponseStatusCode()
			// answers 500 when nobody set one. FrontCacheEngine then stamps that 500 over the app's
			// own status in addResponseHeaders().
			//
			// It stayed hidden because it only bites when the response is still UNCOMMITTED here:
			// an app that wrote through getOutputStream() with a Content-Length has already been
			// flushed, and setStatus() on a committed response is a no-op. An app that wrote a small
			// body through getWriter() has not, and its 200 became a 500 - with the correct body
			// attached, which is what made it invisible to any test asserting on content.
			context.setResponseStatusCode(response.getStatus());
		} else {

			// stand alone mode

			// With passthrough on, the client's own Accept-Encoding goes to the origin untouched -
			// see FrontCacheEngine.isBypassPassthroughEncoding().
			boolean passthroughEncoding = FrontCacheEngine.getFrontCache().isBypassPassthroughEncoding();

			Map<String, List<String>> headers = FCUtils.buildRequestHeaders(request, !passthroughEncoding);
			headers.put(FCHeaders.X_FRONTCACHE_CLIENT_IP, Arrays.asList(new String[]{context.getClientIP()}));
			FCUtils.addPublicForwardedHeaders(headers, context);

			String verb = request.getMethod();
			InputStream requestEntity = getRequestBody(request);
			String uri = context.getRequestURI();

			try {
				ClassicHttpResponse response = forward(client, verb, uri, request, headers, requestEntity, passthroughEncoding);

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


	private void setResponse(ClassicHttpResponse response) throws IOException {

		context.setHttpClientResponse(response);

		setResponse(response.getCode(),
				response.getEntity() == null ? null : response.getEntity().getContent(),
				FCUtils.revertHeaders(response.getHeaders()));
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
	 * The ContentType for a forwarded request entity. HttpClient 5's InputStreamEntity requires one
	 * where 4.5's (stream, length) constructor did not; forward the client's own, null when it did not
	 * declare one or could not be parsed. The header itself is still forwarded separately by
	 * convertHeaders(), and setHeaders() replaces whatever the entity contributed, so the origin sees
	 * exactly one Content-Type. See docs/archive/httpclient5-upgrade-proposal.md section 4.4.
	 *
	 * Only ever called from the POST/PUT/PATCH branches: a GET has no entity, and on that path the
	 * servlet request may legitimately be null.
	 */
	private static ContentType entityContentType(HttpServletRequest request) {
		if (null == request)
			return null;

		String contentType = request.getContentType();
		if (null == contentType)
			return null;

		try {
			return ContentType.parse(contentType);
		} catch (Exception e) {
			return null; // client sent a malformed Content-Type - forward the header, not the parse
		}
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
	ClassicHttpResponse forward(HttpClient httpclient, String verb, String uri, HttpServletRequest request,
			Map<String, List<String>> headers, InputStream requestEntity)
					throws Exception {
		return forward(httpclient, verb, uri, request, headers, requestEntity, false);
	}

	ClassicHttpResponse forward(HttpClient httpclient, String verb, String uri, HttpServletRequest request,
			Map<String, List<String>> headers, InputStream requestEntity, boolean passthroughEncoding)
					throws Exception {

		URL host = context.getOriginURL();
		HttpHost httpHost = FCUtils.getHttpHost(host);
		uri = DUPLICATE_SLASHES.matcher(host.getPath() + uri).replaceAll("/");

		ClassicHttpRequest httpRequest;
		switch (verb.toUpperCase()) {
		case "POST":
			HttpPost httpPost = new HttpPost(uri + context.getRequestQueryString());
			httpRequest = httpPost;
			httpPost.setEntity(new InputStreamEntity(requestEntity, request.getContentLength(), entityContentType(request)));
			break;
		case "PUT":
			HttpPut httpPut = new HttpPut(uri + context.getRequestQueryString());
			httpRequest = httpPut;
			httpPut.setEntity(new InputStreamEntity(requestEntity, request.getContentLength(), entityContentType(request)));
			break;
		case "PATCH":
			HttpPatch httpPatch = new HttpPatch(uri + context.getRequestQueryString());
			httpRequest = httpPatch;
			httpPatch.setEntity(new InputStreamEntity(requestEntity, request.getContentLength(), entityContentType(request)));
			break;
		default:
			httpRequest = new BasicClassicHttpRequest(verb, uri + context.getRequestQueryString());
		}


		try {
			httpRequest.setHeaders(FCUtils.convertHeaders(headers));

			// The one origin call that carries no x-frontcache-request-id, so without the token a
			// bypassed POST from the edge looks exactly like a direct client to the next node.
			EdgeToken.stamp(httpRequest, httpHost.getHostName());

			if (passthroughEncoding)
			{
				// Leave the client's Accept-Encoding alone AND stop HttpClient from decompressing the
				// response, so the origin's Content-Encoding and its compressed bytes reach the client
				// untouched. Applied through the context because the GET branch above builds a
				// BasicClassicHttpRequest, which has no setConfig() of its own.
				HttpClientContext httpContext = HttpClientContext.create();
				httpContext.setRequestConfig(RequestConfig.copy(RequestConfig.DEFAULT)
						.setContentCompressionEnabled(false)
						.build());
				return httpclient.executeOpen(httpHost, httpRequest, httpContext);
			}

			// Always advertise gzip (and only gzip) to the origin. Our HttpClient transparently
			// decodes gzip, so Frontcache always receives plaintext it can parse/cache and never an
			// encoding it cannot handle (e.g. br/zstd). buildRequestHeaders() normalizes this too;
			// setting it here makes the origin call self-contained, independent of the inbound headers.
			httpRequest.setHeader(FCHeaders.ACCEPT_ENCODING, "gzip");
			ClassicHttpResponse originResponse = httpclient.executeOpen(httpHost, httpRequest, null);
			return originResponse;
		} finally {
			// When HttpClient instance is no longer needed,
			// shut down the connection manager to ensure
			// immediate deallocation of all system resources
			// httpclient.getConnectionManager().shutdown();
		}
	}

}