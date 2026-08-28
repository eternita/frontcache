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
import org.frontcache.core.FCUtils;
import org.frontcache.core.WebResponse;
import org.frontcache.guard.GuardRuleEntry;
import org.frontcache.resilience.fr.FallbackConfigEntry;
import org.frontcache.cache.CacheProcessor;
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
import org.frontcache.io.GetResilienceConfigActionResponse;
import org.frontcache.io.NodeStatsActionResponse;
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

		// HttpClient 5 moved these off RequestConfig - socket read timeout to SocketConfig, connect
		// timeout to ConnectionConfig, both on the connection manager. RequestConfig.setResponseTimeout
		// is NOT the old socket timeout. See docs/httpclient5-upgrade-proposal.md section 4.1.
		connectionManager.setDefaultSocketConfig(SocketConfig.custom()
				.setSoTimeout(Timeout.ofMilliseconds(CONNECTION_TIMEOUT))
				.build());
		connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(CONNECTION_TIMEOUT))
				.build());

		return connectionManager;
	}

	private FrontCacheClient(String frontcacheURL) {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setCookieSpec(StandardCookieSpec.IGNORE)
				.build();

	    client = HttpClients.custom()
				.setConnectionManager(newConnectionManager())
				.setDefaultRequestConfig(requestConfig)
				// Management calls are not idempotent (invalidate, put-to-cache) - never retry them.
				// 4.5 said this as DefaultHttpRequestRetryHandler(0, false).
				.disableAutomaticRetries()
				.setKeepAliveStrategy(FCUtils.keepAliveStrategy())
				// A management endpoint must never be followed to somewhere else.
				.disableRedirectHandling()
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
	 * The cheap, pollable view of a node - cache sizes per tier plus JVM heap, CPU and uptime.
	 *
	 * <h2>The fallback is the point</h2>
	 *
	 * The console upgrades independently of the fleet, in either order, so this has to work against a
	 * node that has never heard of {@code get-node-stats}. Such a node answers an unrecognized action
	 * with a PING response rather than an error, which would otherwise render as a panel of dashes with
	 * nothing in any log. Detecting the ping and retrying with {@code get-cache-state} gets the one
	 * number an older node does have - the entry count - and leaves the rest {@code UNKNOWN} so the
	 * console can show it as "not reported" instead of zero.
	 *
	 * Same shape as {@link #getResilienceConfigActionResponse()}, which does this for the 2.7 rename.
	 *
	 * @return the node's stats, or null when the edge could not be reached at all
	 */
	public NodeStatsActionResponse getNodeStatsActionResponse()
	{
		NodeStatsActionResponse actionResponse = nodeStatsActionResponse();

		if (null != actionResponse && FrontcacheAction.PING.equals(actionResponse.getAction()))
		{
			logger.debug(getFrontCacheURL() + " does not know " + FrontcacheAction.GET_NODE_STATS
					+ " - it predates 2.8; falling back to " + FrontcacheAction.GET_CACHE_STATE);
			return fromCacheState(getCacheStateActionResponse());
		}

		return actionResponse;
	}

	private NodeStatsActionResponse nodeStatsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_NODE_STATS));
		String responseStr = null;
		try {
			responseStr = requestFrontCache(urlParameters);
			return jsonMapper.readValue(responseStr.getBytes(), NodeStatsActionResponse.class);

		} catch (Exception e) {
			logger.error("Can't parse response. JSON format is expected for " + responseStr, e);
		}

		return null;
	}

	/**
	 * What a pre-2.8 node can tell us, in the post-2.8 shape.
	 *
	 * {@code get-cache-state} returns a display map whose values are strings; only the total entry
	 * count is worth lifting out of it. Everything else stays UNKNOWN - deliberately, because the
	 * per-tier keys in that map ({@code cached-entries-L1}) exist but are the expensive tier counts,
	 * and reporting some of a node's numbers as though they were all of them is how a half-filled panel
	 * gets mistaken for a half-empty cache.
	 */
	private NodeStatsActionResponse fromCacheState(CacheStatusActionResponse cacheState)
	{
		if (null == cacheState || null == cacheState.getCacheStatus())
			return null;

		NodeStatsActionResponse response = new NodeStatsActionResponse();
		response.setAction(FrontcacheAction.GET_CACHE_STATE);   // says which action actually answered
		response.setResponseStatus(ActionResponse.RESPONSE_STATUS_OK);

		String cachedEntries = cacheState.getCacheStatus().get(CacheProcessor.CACHED_ENTRIES);
		if (null != cachedEntries)
		{
			try {
				response.setCachedEntries(Long.parseLong(cachedEntries.trim()));
			} catch (NumberFormatException e) {
				logger.debug("Can't read " + CacheProcessor.CACHED_ENTRIES + "=" + cachedEntries
						+ " from " + getFrontCacheURL());
			}
		}

		return response;
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
			post.addHeader("Authorization", "Bearer " + siteKey);

		InputStream is = null;
		ClassicHttpResponse response = null;
		try {
			post.setEntity(new UrlEncodedFormEntity(urlParameters));
			response = client.executeOpen(null, post, null);
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

	/**
	 * @return the fallback entries this edge has loaded, or null when the edge could not be reached
	 */
	public Set<FallbackConfigEntry> getFallbackEntries()
	{
		GetFallbackConfigActionResponse actionResponse = getFallbackConfigsActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getFallbackEntries();
	}

	/**
	 * @deprecated pre-2.7 shape: a single-entry map keyed by domain. Use {@link #getFallbackEntries()}.
	 */
	@Deprecated
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

	/**
	 * @return the bot user-agent keywords this edge is using, or null when the edge could not be reached
	 */
	public Set<String> getBotKeywords()
	{
		GetBotsActionResponse actionResponse = getBotsActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getBotKeywords();
	}

	/**
	 * @deprecated pre-2.7 shape: a single-entry map keyed by domain. Use {@link #getBotKeywords()}.
	 */
	@Deprecated
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

	/**
	 * @return the never-cached URL patterns this edge is using, or null when the edge could not be reached
	 */
	public Set<String> getDynamicUrlPatterns()
	{
		GetDynamicURLsActionResponse actionResponse = getDynamicURLsActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getDynamicUrlPatterns();
	}

	/**
	 * @deprecated pre-2.7 shape: a single-entry map keyed by domain. Use {@link #getDynamicUrlPatterns()}.
	 */
	@Deprecated
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
	 * Asks for the raw resilience config, falling back to the pre-2.7 action name when the edge does
	 * not recognize the current one.
	 *
	 * The fallback is needed because an unknown action is not an error on the wire: the management
	 * servlet answers it with a ping response, which would render the console's panel empty with
	 * nothing in any log to explain why. A ping is therefore the signal to retry with the old name -
	 * a precise test, since this client never sends {@code ping} itself.
	 *
	 * @return the response, or null when the edge could not be reached
	 */
	public GetResilienceConfigActionResponse getResilienceConfigActionResponse()
	{
		GetResilienceConfigActionResponse actionResponse = resilienceConfigActionResponse(FrontcacheAction.GET_RESILIENCE_CONFIGS);

		if (null != actionResponse && FrontcacheAction.PING.equals(actionResponse.getAction()))
		{
			logger.debug(getFrontCacheURL() + " does not know " + FrontcacheAction.GET_RESILIENCE_CONFIGS
					+ " - it predates 2.7; retrying with " + FrontcacheAction.GET_HYSTRIX_CONFIGS);
			return resilienceConfigActionResponse(FrontcacheAction.GET_HYSTRIX_CONFIGS);
		}

		return actionResponse;
	}

	private GetResilienceConfigActionResponse resilienceConfigActionResponse(String action)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", action));

		try {
			String responseStr = requestFrontCache(urlParameters);
			return jsonMapper.readValue(responseStr.getBytes(), GetResilienceConfigActionResponse.class);

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * @return the raw contents of the edge's conf/resilience.properties, or null when the edge could
	 *         not be reached
	 */
	public String getResilienceConfig()
	{
		GetResilienceConfigActionResponse actionResponse = getResilienceConfigActionResponse();

		if (null == actionResponse)
			return null;

		return actionResponse.getResilienceConfig();
	}

	/** @deprecated pre-2.7 name of {@link #getResilienceConfigActionResponse()} */
	@Deprecated
	public GetResilienceConfigActionResponse getHystrixConfigActionResponse()
	{
		return getResilienceConfigActionResponse();
	}

	/** @deprecated pre-2.7 name of {@link #getResilienceConfig()} */
	@Deprecated
	public String getHystrixConfig()
	{
		return getResilienceConfig();
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
			post.addHeader("Authorization", "Bearer " + siteKey);

		post.setEntity(new UrlEncodedFormEntity(urlParameters));

		StringBuilder result = new StringBuilder();
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
