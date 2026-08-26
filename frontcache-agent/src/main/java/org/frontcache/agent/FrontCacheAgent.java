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
import java.util.List;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;

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

	private static PoolingHttpClientConnectionManager newConnectionManager() {
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(CONNECTIONS_MAX_TOTAL);
		connectionManager.setDefaultMaxPerRoute(CONNECTIONS_MAX_PER_ROUTE);
		return connectionManager;
	}

	public FrontCacheAgent(String frontcacheURL) {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setSocketTimeout(10000)
				.setConnectTimeout(3000)
				.setCookieSpec(CookieSpecs.IGNORE_COOKIES)
				.build();

	    ConnectionKeepAliveStrategy keepAliveStrategy = new ConnectionKeepAliveStrategy() {
	        @Override
	        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
	            HeaderElementIterator it = new BasicHeaderElementIterator
	                (response.headerIterator(HTTP.CONN_KEEP_ALIVE));
	            while (it.hasNext()) {
	                HeaderElement he = it.nextElement();
	                String param = he.getName();
	                String value = he.getValue();
	                if (value != null && param.equalsIgnoreCase
	                   ("timeout")) {
	                    return Long.parseLong(value) * 1000;
	                }
	            }
	            return 10 * 1000;
	        }
	    };

	    client = HttpClients.custom()
				.setConnectionManager(newConnectionManager())
				.setDefaultRequestConfig(requestConfig)
				.setRetryHandler(new DefaultHttpRequestRetryHandler(0, false))
				.setKeepAliveStrategy(keepAliveStrategy)
				.setRedirectStrategy(new RedirectStrategy() {
					@Override
					public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return false;
					}

					@Override
					public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return null;
					}
				})
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
		CloseableHttpResponse response = (CloseableHttpResponse) client.execute(post);
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
