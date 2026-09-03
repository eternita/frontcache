/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.protocol.RedirectStrategy;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.protocol.HttpContext;

public class FrontCacheAgent {

	private String frontCacheURL;

	private String frontCacheURI;

	private String siteKey = "";

	private final static String IO_URI = "frontcache-io";
	private final static String INVALIDATE = "invalidate";


	private HttpClient client;

	/**
	 * Connections this client may hold, in total and per target node.
	 *
	 * Set explicitly because {@code HttpClients.custom()} otherwise installs Apache's defaults -
	 * <b>20 total, 2 per route</b>. Two per route is the binding constraint here: every call from this
	 * client goes to ONE node, so it is a hard ceiling of two concurrent management calls no matter how
	 * many threads ask. Cache replication (which issues a get + a put per key) and the console's
	 * fan-out across edges both run into it.
	 *
	 * FrontCacheEngine.newClient() has always sized its pool deliberately for the same reason; this
	 * brings the management/replication clients in line.
	 */
	private static final int CONNECTIONS_MAX_TOTAL = 64;
	private static final int CONNECTIONS_MAX_PER_ROUTE = 32;

	private static final int SOCKET_TIMEOUT = 10000;
	private static final int CONNECT_TIMEOUT = 3000;

	private static PoolingHttpClientConnectionManager newConnectionManager() {
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(CONNECTIONS_MAX_TOTAL);
		connectionManager.setDefaultMaxPerRoute(CONNECTIONS_MAX_PER_ROUTE);

		// HttpClient 5 moved these off RequestConfig - socket read timeout to SocketConfig, connect
		// timeout to ConnectionConfig, both on the connection manager. RequestConfig.setResponseTimeout
		// is NOT the old socket timeout. See docs/archive/httpclient5-upgrade-proposal.md section 4.1.
		connectionManager.setDefaultSocketConfig(SocketConfig.custom()
				.setSoTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT))
				.build());
		connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT))
				.build());

		return connectionManager;
	}

	public FrontCacheAgent(String frontcacheURL) {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setCookieSpec(StandardCookieSpec.IGNORE)
				.build();

	    client = HttpClients.custom()
				.setConnectionManager(newConnectionManager())
				.setDefaultRequestConfig(requestConfig)
				// Management calls are not idempotent (invalidate) - never retry them.
				// 4.5 said this as DefaultHttpRequestRetryHandler(0, false).
				.disableAutomaticRetries()
				// Honour the node's own Keep-Alive: timeout=N, defaulting to 10s. Duplicated from
				// FCUtils.keepAliveStrategy() on purpose - the agent must not depend on frontcache-core.
				.setKeepAliveStrategy((response, context) -> {
					Iterator<HeaderElement> it = MessageSupport.iterate(response, HttpHeaders.KEEP_ALIVE);
					while (it.hasNext()) {
						HeaderElement he = it.next();
						String param = he.getName();
						String value = he.getValue();
						if (null != value && param.equalsIgnoreCase("timeout")) {
							try {
								return TimeValue.ofSeconds(Long.parseLong(value));
							} catch (NumberFormatException e) {
								break;
							}
						}
					}
					return TimeValue.ofSeconds(10);
				})
				// A management endpoint must never be followed to somewhere else.
				.disableRedirectHandling()
				.build();

		this.frontCacheURL = frontcacheURL;

		if (frontcacheURL.endsWith("/"))
			this.frontCacheURI = frontcacheURL + IO_URI;
		else
			this.frontCacheURI = frontcacheURL + "/" + IO_URI;
	}

	public FrontCacheAgent(String frontcacheURL, String siteKey) {
		this(frontcacheURL);
		this.siteKey = siteKey;
	}


	/**
	 *
	 * @param filter
	 * @return
	 */
	public String removeFromCache(String filter)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", INVALIDATE));
		urlParameters.add(new BasicNameValuePair("filter", filter));

		try {
			return requestFrontCache(urlParameters);
		} catch (Exception e) {
			e.printStackTrace();
			return "ERROR " + e.getMessage();
		}
	}

	/**
	 *
	 * @param urlParameters
	 * @return
	 * @throws IOException
	 */
	protected String requestFrontCache(List<NameValuePair> urlParameters) throws IOException
	{
		HttpPost post = new HttpPost(frontCacheURI);

//    	post.addHeader("Accept-Encoding", "gzip");

		// Standard bearer auth, the one form every Frontcache caller uses. Spelled out rather than
		// referenced because this module deliberately does not depend on frontcache-core.
		// A node older than 2.9 will not recognise it - those accept only the pre-2.9
		// x-frontcache-site-key header, so an agent this new needs a node at least this new.
		if (null != siteKey)
			post.addHeader("Authorization", "Bearer " + siteKey);

		post.setEntity(new UrlEncodedFormEntity(urlParameters));

		StringBuffer result = new StringBuffer();
		ClassicHttpResponse response = client.executeOpen(null, post, null);
		try {
			BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

			String line = "";
			while ((line = rd.readLine()) != null) {
				result.append(line);
			}
		} finally {
			// close in finally so the pooled connection is released even if reading the body throws
			response.close();
		}

		return result.toString();
	}

	public String getFrontCacheURL() {
		return frontCacheURL;
	}


	@Override
	public String toString() {
		return "FrontCacheAgent [" + frontCacheURL + "]";
	}

}
