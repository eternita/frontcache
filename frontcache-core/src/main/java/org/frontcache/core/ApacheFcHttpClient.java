/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.frontcache.resilience.FC_ThroughCache_HttpClient;
import org.frontcache.resilience.FC_ThroughCache_WebFilter;

/**
 * The Apache-backed {@link FcHttpClient}. This class and the resilience commands are the only places
 * in {@code frontcache-core} that know an HTTP library exists - everything else, including all three
 * reflection-loaded extension points, sees only {@link FcHttpClient}.
 *
 * See docs/archive/httpclient5-upgrade-proposal.md section 5.1.
 */
public class ApacheFcHttpClient implements FcHttpClient {

	/** Read buffer for {@link #fetch(String)}. Bootstrap downloads only - see the interface javadoc. */
	private static final int READ_BUFFER_SIZE = 8 * 1024;

	private final HttpClient client;

	public ApacheFcHttpClient(HttpClient client) {
		this.client = client;
	}

	/**
	 * The underlying Apache client. For {@code frontcache-core} internals that build resilience
	 * commands themselves; not part of {@link FcHttpClient} and not for extension points.
	 */
	public HttpClient getApacheClient() {
		return client;
	}

	@Override
	public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
			throws FrontCacheException {

		// In webfilter mode the origin is the app this filter is installed in, so the way to reach it
		// is its own filter chain, not a socket back to ourselves.
		if (context.isFilterMode())
			return new FC_ThroughCache_WebFilter(context).execute();

		return new FC_ThroughCache_HttpClient(urlStr, requestHeaders, client, context).execute();
	}

	@Override
	public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
			throws FrontCacheException {

		return new FC_ThroughCache_HttpClient(urlStr, requestHeaders, client, context).execute();
	}

	@Override
	public WebResponse fetch(String urlStr) throws FrontCacheException {

		HttpGet httpRequest = new HttpGet(FCUtils.buildRequestURI(urlStr));

		try {
			HttpHost httpHost = FCUtils.getHttpHost(new URL(urlStr));

			// executeOpen(), not execute(): the execute() overload returning an open response is
			// deprecated in 5.3, executeOpen() is its non-deprecated replacement. Same everywhere in
			// the codebase - see FC_BypassCache, which needs an open response it can close from
			// another thread and so cannot use a scoped HttpClientResponseHandler.
			try (ClassicHttpResponse response = client.executeOpen(httpHost, httpRequest, null)) {

				WebResponse webResponse = new WebResponse(urlStr, readBody(response.getEntity()));
				webResponse.setStatusCode(response.getCode());
				webResponse.setHeaders(FCUtils.revertHeaders(response.getHeaders()));

				return webResponse;
			}
		} catch (IOException e) {
			throw new FrontCacheException("Can't fetch " + urlStr, e);
		}
	}

	/** Reads an entity fully. Returns an empty array for a bodyless response (204, 304, HEAD). */
	private static byte[] readBody(HttpEntity entity) throws IOException {

		if (null == entity)
			return new byte[0];

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (InputStream is = entity.getContent()) {
			byte[] buffer = new byte[READ_BUFFER_SIZE];
			int bytesRead;
			while (-1 != (bytesRead = is.read(buffer)))
				baos.write(buffer, 0, bytesRead);
		}

		return baos.toByteArray();
	}
}
