/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.client;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.frontcache.guard.GuardRuleEntry;
import org.frontcache.hystrix.fr.FallbackConfigEntry;
import org.frontcache.io.ActionResponse;
import org.frontcache.io.BackupActionResponse;
import org.frontcache.io.CacheStatusActionResponse;
import org.frontcache.io.DumpKeysActionResponse;
import org.frontcache.io.FrontcacheAction;
import org.frontcache.io.GetBotsActionResponse;
import org.frontcache.io.GetDynamicURLsActionResponse;
import org.frontcache.io.GetFallbackConfigActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
import org.frontcache.io.GetGuardRulesActionResponse;
import org.frontcache.io.GetGuardConfigActionResponse;
import org.frontcache.io.GetHystrixConfigActionResponse;
import org.frontcache.io.PutToCacheActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FrontCacheClient {

	private String frontCacheURL;

	private String frontCacheURI;

	private String siteKey = "";

	private final static String IO_URI = "frontcache-io";

	private ObjectMapper jsonMapper = new ObjectMapper();

	private HttpClient client;

	private Logger logger = LoggerFactory.getLogger(FrontCacheClient.class);

	public static int CONNECTION_TIMEOUT = 5000;


	private FrontCacheClient(String frontcacheURL) {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setSocketTimeout(CONNECTION_TIMEOUT)
				.setConnectTimeout(CONNECTION_TIMEOUT)
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

	/**
	 *
	 * @param frontcacheURL
	 * @param siteKey
	 */
	public FrontCacheClient(String frontcacheURL, String siteKey) {
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
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.INVALIDATE));
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
	 * @return
	 */
	public String removeFromCacheAll()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.INVALIDATE));
		urlParameters.add(new BasicNameValuePair("filter", "*"));

		try {
			return requestFrontCache(urlParameters);
		} catch (Exception e) {
			e.printStackTrace();
			return "ERROR " + e.getMessage();
		}
	}

	/**
	 * Triggers purge of expired entries on the edge.
	 *
	 * @return raw edge response, or an error message string
	 */
	public String purge()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.PURGE));

		try {
			return requestFrontCache(urlParameters);
		} catch (Exception e) {
			e.printStackTrace();
			return "ERROR " + e.getMessage();
		}
	}

	/**
	 * Triggers a backup of the on-disk (L2) index on the edge.
	 *
	 * @return BackupActionResponse with the status message, or null if the edge is not available
	 */
	public BackupActionResponse backup()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.BACKUP_INDEX));

		try {
			String responseStr = requestFrontCache(urlParameters);
			BackupActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), BackupActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public Map<String, String> getCacheState()
	{
		CacheStatusActionResponse actionResponse = getCacheStateActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getCacheStatus();

	}

	/**
	 *
	 * @return
	 */
	public CacheStatusActionResponse getCacheStateActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_CACHE_STATE));
		String responseStr = null;
		try {
			responseStr = requestFrontCache(urlParameters);
			CacheStatusActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), CacheStatusActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			logger.error("Can't parse response. JSON format is expected for " + responseStr, e);
		}

		return null;
	}

	/**
	 * Writes keys to provided output stream
	 *
	 * @param os
	 * @return
	 */
	public boolean getCachedKeys(OutputStream os)
	{
		boolean success = true;
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_CACHED_KEYS));

		HttpPost post = new HttpPost(frontCacheURI);

//    	post.addHeader("Accept-Encoding", "gzip");

		if (null != siteKey)
			post.addHeader(FCHeaders.X_FRONTCACHE_SITE_KEY, siteKey);

		InputStream is = null;
		CloseableHttpResponse response = null;
		try {
			post.setEntity(new UrlEncodedFormEntity(urlParameters));
			response = (CloseableHttpResponse) client.execute(post);
			is = response.getEntity().getContent();
	        int bytesRead = 0;
	        int bufferSize = 4000;
	         byte[] byteBuffer = new byte[bufferSize];
	         while ((bytesRead = is.read(byteBuffer)) != -1) {
	             os.write(byteBuffer, 0, bytesRead);
	         }
		} catch (Exception e) {
			success = false;
			e.printStackTrace();
		} finally {
			try {
				if (null != is)
					is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			// close the response too: if getContent() threw, 'is' is null and the connection would leak
			try {
				if (null != response)
					response.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return success;
	}

	/**
	 * Triggers dumping of cached keys to a file on the edge (./warmer dir).
	 *
	 * @return DumpKeysActionResponse with the status message, or null if the edge is not available
	 */
	public DumpKeysActionResponse dumpKeys()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.DUMP_KEYS));

		try {
			String responseStr = requestFrontCache(urlParameters);
			DumpKeysActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), DumpKeysActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	/**
	 *
	 * @return
	 */
	public GetFallbackConfigActionResponse getFallbackConfigsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_FALLBACK_CONFIGS));

		try {
			String responseStr = requestFrontCache(urlParameters);
			GetFallbackConfigActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetFallbackConfigActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public Map <String, Set<FallbackConfigEntry>> getFallbackConfigs()
	{
		GetFallbackConfigActionResponse actionResponse = getFallbackConfigsActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getFallbackConfigs();
	}


	/**
	 *
	 * @return
	 */
	public GetBotsActionResponse getBotsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_BOTS));

		try {
			String responseStr = requestFrontCache(urlParameters);
			GetBotsActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetBotsActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public Map<String, Set<String>> getBots()
	{
		GetBotsActionResponse actionResponse = getBotsActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getBots();
	}


	/**
	 *
	 * @return
	 */
	public GetDynamicURLsActionResponse getDynamicURLsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_DYNAMIC_URLS));

		try {
			String responseStr = requestFrontCache(urlParameters);
			GetDynamicURLsActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetDynamicURLsActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public Map<String, Set<String>> getDynamicURLs()
	{
		GetDynamicURLsActionResponse actionResponse = getDynamicURLsActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getDynamicURLs();
	}

	/**
	 *
	 * @return guard rules this edge is running, in evaluation order, with hit counts
	 */
	public GetGuardRulesActionResponse getGuardRulesActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_GUARD_RULES));

		try {
			String responseStr = requestFrontCache(urlParameters);
			GetGuardRulesActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetGuardRulesActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public List<GuardRuleEntry> getGuardRules()
	{
		GetGuardRulesActionResponse actionResponse = getGuardRulesActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getGuardRules();
	}

	/**
	 *
	 * @return
	 */
	public GetGuardConfigActionResponse getGuardConfigActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_GUARD_CONFIG));

		try {
			String responseStr = requestFrontCache(urlParameters);
			GetGuardConfigActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetGuardConfigActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public String getGuardConfig()
	{
		GetGuardConfigActionResponse actionResponse = getGuardConfigActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getGuardConfig();
	}

	/**
	 *
	 * @return
	 */
	public GetHystrixConfigActionResponse getHystrixConfigActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_HYSTRIX_CONFIGS));

		try {
			String responseStr = requestFrontCache(urlParameters);
			GetHystrixConfigActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetHystrixConfigActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public String getHystrixConfig()
	{
		GetHystrixConfigActionResponse actionResponse = getHystrixConfigActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getHystrixConfig();
	}


	/**
	 *
	 * @return
	 */
	public GetFromCacheActionResponse getFromCacheActionResponse(String key)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_FROM_CACHE));
		urlParameters.add(new BasicNameValuePair("key", key));

		try {
			String responseStr = requestFrontCache(urlParameters);
			logger.debug("getFromCache(" + this + ") -> done");
			GetFromCacheActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetFromCacheActionResponse.class);
			return actionResponse;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public WebResponse getFromCache(String key)
	{
		GetFromCacheActionResponse actionResponse = getFromCacheActionResponse(key);

		if (null == actionResponse)
			return null;

		return actionResponse.getValue();
	}

	/**
	 * Pushes a WebResponse entry into the remote node's cache.
	 * Used by the replication engine to write entries from a source edge to a target edge.
	 *
	 * @param key         the cache key (normalised path)
	 * @param webResponse the entry to store
	 * @return true if the entry was stored successfully
	 */
	public boolean putToCache(String key, WebResponse webResponse)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.PUT_TO_CACHE));
		urlParameters.add(new BasicNameValuePair("key", key));

		try {
			String json = jsonMapper.writeValueAsString(webResponse);
			urlParameters.add(new BasicNameValuePair("webResponse", json));

			String responseStr = requestFrontCache(urlParameters);
			PutToCacheActionResponse actionResponse =
					jsonMapper.readValue(responseStr.getBytes(), PutToCacheActionResponse.class);
			return ActionResponse.RESPONSE_STATUS_OK.equals(actionResponse.getResponseStatus());
		} catch (Exception e) {
			logger.error("putToCache failed: key={}", key, e);
			return false;
		}
	}

	/**
	 * Fetches all cached keys from the remote node and returns them as a Set.
	 * Used by the replication engine to compute the diff between source and target.
	 *
	 * @return set of cached keys, or empty set on error
	 */
	public Set<String> getCachedKeysSet()
	{
		Set<String> keys = new HashSet<String>();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		if (getCachedKeys(baos)) {
			try {
				String[] lines = baos.toString("UTF-8").split("\n");
				for (String line : lines) {
					String trimmed = line.trim();
					if (!trimmed.isEmpty()) {
						keys.add(trimmed);
					}
				}
			} catch (UnsupportedEncodingException e) {
				logger.error("Failed to parse keys", e);
			}
		}
		return keys;
	}

	/**
	 *
	 * @param urlParameters
	 * @return
	 * @throws IOException
	 */
	private String requestFrontCache(List<NameValuePair> urlParameters) throws IOException
	{
		HttpPost post = new HttpPost(frontCacheURI);

//    	post.addHeader("Accept-Encoding", "gzip");

		if (null != siteKey)
			post.addHeader(FCHeaders.X_FRONTCACHE_SITE_KEY, siteKey);

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

	/**
	 *  http://localhost:8080/ -> localhost:8080
	 * @return
	 */
	public String getName() {
		String name = frontCacheURL.trim();

		int idx = name.indexOf("//");
		if (-1 < idx)
			name = name.substring(idx + "//".length());

		idx = name.indexOf(":"); // localhost:8080 -> localhost
		if (-1 < idx)
			name = name.substring(0, idx);

//		idx = name.indexOf("/"); //
//		if (-1 < idx)
//			name = name.substring(0, idx);

		// e.g. localhost
		return name;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((frontCacheURI == null) ? 0 : frontCacheURI.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		FrontCacheClient other = (FrontCacheClient) obj;
		if (frontCacheURI == null) {
			if (other.frontCacheURI != null)
				return false;
		} else if (!frontCacheURI.equals(other.frontCacheURI))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "FrontCacheClient [" + frontCacheURL + "]";
	}

}
