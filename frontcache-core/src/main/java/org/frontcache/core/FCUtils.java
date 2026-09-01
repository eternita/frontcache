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
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.message.BasicHeader;
import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.resilience.FC_ThroughCache_HttpClient;
import org.frontcache.resilience.FC_ThroughCache_WebFilter;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FCUtils {

	private FCUtils() {
	}

	private static Logger logger = LoggerFactory.getLogger(FCUtils.class);

    private static final String[] CLIENT_IP_SOURCE_HEADER_LIST = {
    		FCHeaders.X_FRONTCACHE_CLIENT_IP,
            "x-forwarded-for",
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
    };

	public static boolean isWebComponentSubjectToCache(Map<String, Long> expireTimeMap)
	{
		if (null == expireTimeMap)
			return false;

		if (null == expireTimeMap.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT) && null == expireTimeMap.get(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER))
			return false;

		if (0 == expireTimeMap.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT) && 0 == expireTimeMap.get(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER))
			return false;

		return true;
	}

	// true - save to cache
	public static boolean isWebComponentCacheableForClientType(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMap must not be empty for clientType=" + clientType);
			return false;
		}

		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return false;
		}

		if (CacheProcessor.NO_CACHE == expireTimeMillis)
			return false;

		return true;
	}

	/**
	 * Check with current time if expired
	 *
	 * @param clientType {bot | browser}
	 * @return
	 */
	public static boolean isWebComponentExpired(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMap must not be empty for clientType=" + clientType);
			return true;
		}

		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return true;
		}

		if (CacheProcessor.CACHE_FOREVER == expireTimeMillis)
			return false;

		if (System.currentTimeMillis() > expireTimeMillis)
			return true;

		return false;
	}




	/**
	 * GET method only for text requests
	 *
	 * for cache processor - it can use both (httpClient or filter)
	 *
	 * @param urlStr
	 * @param httpRequest
	 * @param httpResponse
	 * @return
	 */
	public static WebResponse dynamicCall(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) throws FrontCacheException
    {
		// add include-level header - to trace include tree
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL, "" + context.getIncludeLevel());

		// add request-id header - to distinguish if sent by frontcache or not (for include processing)
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());

		// The filter-mode / standalone split lives in the FcHttpClient implementation now - reaching
		// the origin through the app's own filter chain is just another origin transport.
		return client.fetch(urlStr, requestHeaders, context);
    }

	/**
	 * for includes ONLY - they allways use httpClient
	 */
	public static WebResponse includeDynamicCallHttpClient(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) throws FrontCacheException
    {
		// add request-id header - to trace include tree
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());

		// add header with client IP - unless it was already resolved on the request thread (async
		// includes pre-resolve it there, before the servlet request is recycled; see
		// ConcurrentIncludeProcessor.parseIncludes). The context caches the value the engine stamped
		// in init(), so this is a map read rather than another 13-header scan, and it is still the
		// right answer on a worker thread whose servlet request has been recycled.
		if (!requestHeaders.containsKey(FCHeaders.X_FRONTCACHE_CLIENT_IP))
			addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_CLIENT_IP, context.getClientIP());

		return client.fetchOverHttp(urlStr, requestHeaders, context);
    }

	public static String getClientIP(HttpServletRequest request) {
		// request may be null or a container-recycled servlet request: async includes are resolved in
		// a background thread after the top-level response has completed, so the original request is
		// gone by then. Reading a header off it would throw (NPE / "request does not exist") - which
		// used to abort the whole include before the origin call even ran (100k+ NPEs/day on
		// coin-visit-counter). The client IP is a best-effort header, so degrade to "unresolved"
		// instead of failing the include.
		if (null == request)
			return "unresolved";
		try {
			for (String header : CLIENT_IP_SOURCE_HEADER_LIST) {
				String ip = request.getHeader(header);
				if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
					return ip;
				}
			}
			return request.getRemoteAddr();
		} catch (Exception ex) {
			logger.info("Can't resolve client IP from request (likely recycled): " + ex);
		}
		return "unresolved";
	}

	/**
	 * Detects a structurally-invalid request query string whose parameter <em>names</em> carry the
	 * HTML-entity artifact {@code amp;} (raw, or percent-encoded as {@code amp%3B}).
	 * <p>
	 * A broken crawler that fails to decode {@code &amp;} in the origin's hrefs turns a real
	 * parameter such as {@code &pagingPage_ci=2} into a bogus parameter named
	 * {@code amp;pagingPage_ci}, and the {@code amp;} prefix compounds ({@code amp;amp;...}) on every
	 * crawl hop. These are never legitimate parameters; worse, each unique permutation is a distinct
	 * cache key, so left unchecked they all miss the cache and flood the origin (~26% of prod traffic
	 * on fc-ap). Such requests are rejected with 400 before reaching cache or origin.
	 *
	 * @param queryString raw request query string, optionally starting with '?'; may be null/empty
	 * @return true if any parameter name is or starts with {@code amp;}
	 */
	public static boolean hasMalformedQueryParams(String queryString)
	{
		if (null == queryString || queryString.isEmpty())
			return false;

		// strip the leading '?' (getRequestQueryString() prefixes one)
		String qs = ('?' == queryString.charAt(0)) ? queryString.substring(1) : queryString;

		for (String pair : qs.split("&"))
		{
			if (pair.isEmpty())
				continue;

			int eq = pair.indexOf('=');
			String rawName = (-1 < eq) ? pair.substring(0, eq) : pair;

			String name;
			try {
				name = URLDecoder.decode(rawName, "UTF-8"); // amp%3B... -> amp;...
			} catch (Exception e) {
				name = rawName; // not valid percent-encoding: check the raw form
			}

			if (name.startsWith("amp;"))
				return true;
		}

		return false;
	}

	/**
	 * Helper to work with Map<String, List<String>>
	 *
	 * @param requestHeaders
	 * @param key
	 * @param name
	 */
	private static void addHeader(Map<String, List<String>> requestHeaders, String key, String name)
	{
		requestHeaders.put(key, Arrays.asList(new String[]{name}));
		return;
	}


	/**
	 * is used in ServletFilter mode
	 *
	 * @param url
	 * @param originWrappedResponse
	 * @return
	 * @throws FrontCacheException
	 * @throws IOException
	 */
	public static WebResponse httpResponse2WebComponent(String url, FrontCacheHttpResponseWrapper originWrappedResponse, RequestContext context) throws FrontCacheException, IOException
	{
		WebResponse webResponse = null;

		String contentType = originWrappedResponse.getContentType();
		String dataStr = originWrappedResponse.getContentString();
		byte[] data = null;
		if (null != dataStr)
			data = dataStr.getBytes();
		else
			logger.info(url + " has response with no data");


		if (null == contentType || -1 == contentType.indexOf("text"))
		{
			contentType = "text"; //
		}

		webResponse = parseWebComponent(url, data, FCUtils.revertHeaders(originWrappedResponse));

		webResponse.setStatusCode(originWrappedResponse.getStatus());

		// get headers
		// case-insensitive: HTTP header names are case-insensitive (RFC 7230) and
		// intermediaries (e.g. Cloudflare) may re-case them - so component directives
		// like x-frontcache-component-maxage must resolve regardless of wire casing
    	Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
		for (String headerName : originWrappedResponse.getHeaderNames())
		if (isIncludedHeaderToResponse(headerName))
		{
			List<String> hValues = headers.get(headerName);
			if(null == hValues)
			{
				hValues = new ArrayList<String>();
				headers.put(headerName, hValues);
			}
			hValues.add(originWrappedResponse.getHeader(headerName));
		}
		webResponse.setHeaders(headers);

		// filter may not set up content type yet -> check and setup
		if (null != dataStr && null == headers.get(FCHeaders.CONTENT_TYPE))
		{
			webResponse.addHeader(FCHeaders.CONTENT_TYPE, contentType);
		}

		if (null != headers.get(FCHeaders.X_FRONTCACHE_FALLBACK_IS_USED))
			context.setFallbackUsed();

		return webResponse;
	}

	/** Read buffer for pulling an origin body. 4000 bytes was under one TCP window's worth per read. */
	private static final int ORIGIN_READ_BUFFER_SIZE = 16 * 1024;

	/** Fallback capacity when the origin declares no usable Content-Length. */
	private static final int ORIGIN_BODY_DEFAULT_CAPACITY = 16 * 1024;

	/**
	 * Initial capacity for the buffer collecting an origin body.
	 *
	 * Capped, because Content-Length is attacker-influenced in the general case and a declared
	 * multi-gigabyte length must not become a multi-gigabyte allocation up front - the buffer still
	 * grows to fit a genuinely large body, just from a sane starting point.
	 */
	private static final int ORIGIN_BODY_MAX_PREALLOC = 8 * 1024 * 1024;

	private static int originBodyCapacity(HttpEntity entity)
	{
		if (null == entity)
			return 32; // no body coming - do not allocate for one

		long contentLength = entity.getContentLength(); // -1 when unknown (e.g. chunked)
		if (0 < contentLength && contentLength <= ORIGIN_BODY_MAX_PREALLOC)
			return (int) contentLength;

		return ORIGIN_BODY_DEFAULT_CAPACITY;
	}

	public static WebResponse httpResponse2WebComponent(String url, ClassicHttpResponse response, RequestContext context) throws FrontCacheException, IOException
	{


		int httpResponseCode = response.getCode();


		String contentType = "";
		Header contentTypeHeader = response.getFirstHeader(FCHeaders.CONTENT_TYPE);
		if (null != contentTypeHeader)
			contentType = contentTypeHeader.getValue();

		WebResponse webResponse = null;

		byte[] respData = null;
		// entity is null for bodyless responses (e.g. 304 Not Modified from a conditional request,
		// or 204 No Content). Guard it - dereferencing getContent() on null NPEs, which would escape
		// run() as a command FAILURE and serve a fallback instead of relaying the bodyless response.
		HttpEntity entity = response.getEntity();
		// Sized from Content-Length when the origin gives a usable one. The default ByteArrayOutputStream
		// starts at 32 bytes and doubles, so a 200 KB page used to take ~13 reallocations, each copying
		// everything accumulated so far - roughly 400 KB of copying to collect 200 KB.
		ByteArrayOutputStream baos = new ByteArrayOutputStream(originBodyCapacity(entity));
		if (null != entity)
		{
			InputStream is = entity.getContent();
			try {
				int bytesRead = 0;
				byte[] byteBuffer = new byte[ORIGIN_READ_BUFFER_SIZE];
				while ((bytesRead = is.read(byteBuffer)) != -1) {
					baos.write(byteBuffer, 0, bytesRead);
				}
			} catch (Exception e) {
				// Deliberately still swallowed: a failed/truncated read degrades to whatever was read,
				// exactly as before. Caching a truncated page is a real problem, but it is a behaviour
				// question, not a performance one - see the perf proposal, section 4.5.
				e.printStackTrace();
			}
		}
		respData = baos.toByteArray(); // empty byte[] when there is no entity

		Map<String, List<String>> headers = revertHeaders(response.getHeaders());
		webResponse = parseWebComponent(url, respData, headers);

		webResponse.setHeaders(headers);
		webResponse.setStatusCode(httpResponseCode);
		if (null == headers.get(FCHeaders.CONTENT_TYPE))
			webResponse.addHeader(FCHeaders.CONTENT_TYPE, contentType);

		if (httpResponseCode < 200 || httpResponseCode > 499) // 2XX - OK, 3XX - REDIRECTS, 4XX - client error (e.g. page not found)
		{
//			logger.error(new String(respData));
			// error
			throw new FrontCacheException("Wrong response code " + httpResponseCode + " for " + url);
		}

		// process redirects
		Header locationHeader = response.getFirstHeader("Location");
		if (null != locationHeader)
		{
			String originLocation = locationHeader.getValue();

			String fcLocation = transformRedirectURL(originLocation, context);
			webResponse.getHeaders().remove("Location");
			webResponse.addHeader("Location", fcLocation);
		}

		Header fallbackUsedHeader = response.getFirstHeader(FCHeaders.X_FRONTCACHE_FALLBACK_IS_USED);
		if (null != fallbackUsedHeader)
			context.setFallbackUsed();

		return webResponse;
	}

	public static String transformRedirectURL(String originLocation, RequestContext context)
	{
		String fcLocation = null;
		String protocol = getRequestProtocol(originLocation);

		if ("".equals(protocol)) // originLocation is relative path, so protocol is undefined
			protocol = context.getFrontCacheProtocol(); // use current protocol from context

		if ("https".equalsIgnoreCase(protocol))
			fcLocation = "https://" + context.getFrontCacheHost() + ":" + context.getFrontCacheHttpsPort() + buildRequestURI(originLocation);
		else // http
			fcLocation = "http://" + context.getFrontCacheHost() + ":" + context.getFrontCacheHttpPort() + buildRequestURI(originLocation);

		return fcLocation;
	}



    /**
     * revert header from HttpClient format (call to origin) to Map
     *
     * @param headers
     * @return
     */
    public static Map<String, List<String>> revertHeaders(Header[] headers) {
    	// case-insensitive map - HTTP header names are case-insensitive (RFC 7230);
    	// intermediaries (e.g. Cloudflare) may re-case them, so x-frontcache-* directives
    	// must resolve regardless of wire casing
    	Map<String, List<String>> map = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
		for (Header header : headers) {
			String name = header.getName();

			if (isIncludedHeaderToResponse(name))
			{
				List<String> hValues = map.get(name);
				if(null == hValues)
				{
					hValues = new ArrayList<String>();
					map.put(name, hValues);
				}
				hValues.add(header.getValue());
			}
		}
		return map;
	}

    public static Map<String, List<String>> revertHeaders(HttpServletResponse response) {
    	// case-insensitive map - see revertHeaders(Header[]) above
    	Map<String, List<String>> map = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
		for (String name : response.getHeaderNames())
		{
			if (isIncludedHeaderToResponse(name))
			{
				List<String> hValues = map.get(name);
				if(null == hValues)
				{
					hValues = new ArrayList<String>();
					map.put(name, hValues);
				}
				hValues.add(response.getHeader(name));
			}
		}
		return map;
	}

	private static boolean isIncludedHeaderToResponse(String headerName) {
		String name = headerName.toLowerCase();

		switch (name) {
			case "content-length": // do not use 'content-length' because 'transfer-encoding' is used
			case "transfer-encoding": // put it here to avoid duplicates
				return false;
			default:
				return true;
		}
	}

	public static Header[] convertHeaders(Map<String, List<String>> headers) {
		List<Header> list = new ArrayList<>();
		for (String name : headers.keySet()) {
			for (String value : headers.get(name)) {
				list.add(new BasicHeader(name, value));
			}
		}
		return list.toArray(new BasicHeader[0]);
	}

	/**
	 *
	 * @param request
	 * @return
	 */
	public static String getRequestURL(HttpServletRequest request)
	{
        String requestURL = request.getRequestURL().toString();

        if ("GET".equals(request.getMethod()))
        {
        	// add parameters for storing
        	// POST method parameters are not stored because they can be huge (e.g. file upload)
        	//
        	// Asked via getQueryString(), not getParameterMap(). The latter makes the container parse
        	// and materialise the entire parameter map, solely to learn whether the query string is
        	// empty - which getQueryString() answers on its own.
        	String queryString = request.getQueryString();
        	if (null != queryString && !queryString.isEmpty())
        		requestURL = requestURL + "?" + queryString;
        }
        return requestURL;
	}

    /**
     * return true if the client requested gzip content
     *
     * @param contentEncoding
     * @return true if the content-encoding containg gzip
     */
    public static boolean isGzipped(String contentEncoding) {
        return contentEncoding.contains("gzip");
    }

	/**
	 * wrap String to WebResponse.
	 * Check for header - extract caching options.
	 *
	 * @param contentStr
	 * @return
	 */
	private static final WebResponse parseWebComponent (String urlStr, byte[] content, Map<String, List<String>> headers) // , String contentType
	{
		String cacheMaxAgeSecStr = null;
		String refreshTypeStr = null;
		String cacheLevelStr = null;
		byte[] outContentBody = content;

		Collection<String> collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		if (null != collStr && !collStr.isEmpty())
			cacheMaxAgeSecStr = collStr.iterator().next();

		collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_REFRESH_TYPE);
		if (null != collStr && !collStr.isEmpty())
			refreshTypeStr = collStr.iterator().next();

		collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_CACHE_LEVEL);
		if (null != collStr && !collStr.isEmpty())
			cacheLevelStr = collStr.iterator().next();

		WebResponse component = new WebResponse(urlStr, outContentBody, cacheMaxAgeSecStr, refreshTypeStr);

		if (null != cacheLevelStr)
			component.setCacheLevel(cacheLevelStr);

		// set invalidation tags
		Collection<String> tagsList = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS);
		if (null != tagsList)
			for (String tagsStr : tagsList)
				component.addTags(Arrays.asList(tagsStr.split(FCHeaders.COMPONENT_TAGS_SEPARATOR)));

		return component;
	}

	/**
	 * Builds a {@link WebResponse} from a body and a header map, applying the same
	 * {@code x-frontcache-component-*} reading every origin response goes through.
	 *
	 * The public door onto {@link #parseWebComponent}, for the one caller that has a fragment and its
	 * headers but no HTTP response to hand: the include processor unpacking a combined response
	 * (docs/combine-reduce-proposal.md section 6). Routing it here rather than letting that code
	 * assemble a WebResponse itself is what keeps maxAge, tags, refresh type and cache level meaning
	 * exactly the same for a combined member as for a single include - the invariant in section 4.2,
	 * which is silent when broken.
	 *
	 * @param headers the part's headers; a case-insensitive map is expected (header names are
	 *                case-insensitive per RFC 7230) and is what the caller is given
	 */
	public static WebResponse buildWebComponent(String url, byte[] content, Map<String, List<String>> headers)
	{
		WebResponse webResponse = parseWebComponent(url, content, headers);
		webResponse.setHeaders(headers);

		return webResponse;
	}

	public static long maxAgeStr2Int(String maxAgeStr)
	{
		try
		{
			int multiplyPrefix = 1;
			if ("forever".equalsIgnoreCase(maxAgeStr.trim())) // forever
			{
				return CacheProcessor.CACHE_FOREVER;
			} else if (maxAgeStr.endsWith("d")) { // days
				maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
				multiplyPrefix = 86400; // 24 * 60 * 60
			} else if (maxAgeStr.endsWith("h")) { // hours
				maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
				multiplyPrefix = 3600; // 60 * 60
			} else if (maxAgeStr.endsWith("m")) { // minutes
				maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
				multiplyPrefix = 60;
			} else if (maxAgeStr.endsWith("s")) { // seconds
				maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
				multiplyPrefix = 1;
			} else {
				// seconds
			}

			return multiplyPrefix * Integer.parseInt(maxAgeStr); // time to live in cache in seconds
		} catch (Exception e) {
			logger.info("can't parse component maxage - " + maxAgeStr + " defalut is used (NO_CACHE)");
			return CacheProcessor.NO_CACHE;
		}
	}

	public static String buildRequestURI(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri;
	}


	/**
	 * http://localhost:8080/coin_instance_details.htm? -> /coin_instance_details.htm?
	 *
	 * @param urlStr
	 * @return
	 */
	public static String buildRequestURI(String urlStr) {

		String outStr = urlStr;
		int idx = urlStr.indexOf("//");
		if (-1 < idx)
		{
			idx = urlStr.indexOf("/", idx + "//".length());
			if (-1 < idx)
				outStr = urlStr.substring(idx);
		}

		idx = outStr.indexOf("?"); // has params
		if (-1 < idx)
		{
			String uri = outStr.substring(0, idx + 1);
			String queryParams = outStr.substring(idx + 1);

			// encode / decode params
			try {
				Map<String, List<String>> parameterMap = splitQueryParameters(queryParams);

				StringBuilder queryParamsSB = new StringBuilder(queryParams.length() + 16);

				for (String key : parameterMap.keySet())
				{
					for (String value : parameterMap.get(key))
					{
						if (null != value)
						{
							if (queryParamsSB.length() > 1)
								queryParamsSB.append("&");

							queryParamsSB.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"));
						}
					}
				}

				queryParams = queryParamsSB.toString();
			} catch (Exception e) {
				logger.error("error buildRequestURI for " + urlStr, e);
			}

			outStr = uri + queryParams;
		}

		return outStr;
	}


	public static Map<String, List<String>> splitQueryParameters(String paramsStr) throws UnsupportedEncodingException {
		final Map<String, List<String>> queryPairs = new LinkedHashMap<String, List<String>>();
		final String[] pairs = paramsStr.split("&");
		for (String pair : pairs) {
			final int idx = pair.indexOf("=");
			final String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
			if (!queryPairs.containsKey(key)) {
				queryPairs.put(key, new LinkedList<String>());
			}
			final String value = idx > 0 && pair.length() > idx + 1
					? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : null;
			queryPairs.get(key).add(value);
		}
		return queryPairs;
	}

	/**
	 * http://localhost:8080/coin_instance_details.htm? -> http
	 * @param urlStr
	 * @return
	 */
	private static String getRequestProtocol(String urlStr) {

		int idx = urlStr.indexOf(":");
		if (-1 < idx)
			return urlStr.substring(0, idx);

		return ""; // default
	}

	/**
	 * The keep-alive strategy shared by the engine's origin client and FrontCacheClient: honour the
	 * origin's own {@code Keep-Alive: timeout=N}, defaulting to 10s when it does not say.
	 *
	 * Extracted during the HttpClient 5 migration - the same anonymous class was pasted into
	 * FrontCacheEngine and FrontCacheClient (and into FrontCacheAgent, which cannot share it because
	 * the agent must not depend on frontcache-core). 5.x also replaced the hand-rolled
	 * BasicHeaderElementIterator with MessageSupport.iterate().
	 */
	public static ConnectionKeepAliveStrategy keepAliveStrategy() {
		return (response, context) -> {
			Iterator<HeaderElement> it = MessageSupport.iterate(response, HttpHeaders.KEEP_ALIVE);
			while (it.hasNext()) {
				HeaderElement he = it.next();
				String param = he.getName();
				String value = he.getValue();
				if (null != value && param.equalsIgnoreCase("timeout")) {
					try {
						return TimeValue.ofSeconds(Long.parseLong(value));
					} catch (NumberFormatException e) {
						break; // origin sent a non-numeric timeout - fall through to the default
					}
				}
			}
			return TimeValue.ofSeconds(10);
		};
	}

	public static HttpHost getHttpHost(URL host) {
		// HttpClient 5 reordered these: 4.5 was (hostname, port, scheme), 5.x is (scheme, hostname,
		// port). Same arity, so the only thing that caught it was the type change.
		HttpHost httpHost = new HttpHost(host.getProtocol(), host.getHost(), host.getPort());
		return httpHost;
	}

	public static Map<String, List<String>> buildRequestHeaders(HttpServletRequest request) {
		return buildRequestHeaders(request, true);
	}

	/**
	 * @param normalizeAcceptEncoding true to replace the client's Accept-Encoding with {@code gzip}
	 *        (what every cacheable path wants - see below); false to forward the client's own, which
	 *        only the bypass path does, and only when encoding passthrough is enabled
	 */
	public static Map<String, List<String>> buildRequestHeaders(HttpServletRequest request, boolean normalizeAcceptEncoding) {

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		Enumeration<String> headerNames = request.getHeaderNames();
		if (headerNames != null) {
			while (headerNames.hasMoreElements()) {
				String name = headerNames.nextElement();
				if (isIncludedHeader(name)) {
					Enumeration<String> values = request.getHeaders(name);
					while (values.hasMoreElements()) {
						String value = values.nextElement();

						List<String> hValues = headers.get(name);
						if(null == hValues)
						{
							hValues = new ArrayList<String>();
							headers.put(name, hValues);
						}
						hValues.add(value);
					}
				}
			}
		}

		// Normalize the encoding we advertise to the origin to gzip only.
		// Our HttpClient transparently decodes gzip/deflate but NOT brotli (br) or zstd - if the
		// origin returned br we would cache that blob and serve it to clients that never requested
		// it (ignoring Vary), and the include processor couldn't parse the compressed body.
		// Forcing gzip means FC always holds plaintext; compression to the client is (re)applied
		// at the edge (nginx / CDN).
		if (normalizeAcceptEncoding)
		{
			headers.keySet().removeIf(name -> FCHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name));
			headers.put(FCHeaders.ACCEPT_ENCODING, new ArrayList<String>(Arrays.asList("gzip")));
		}

		return headers;
	}

	private static boolean isIncludedHeader(String headerName) {
		String name = headerName.toLowerCase();

		switch (name) {
			case "host":
			case "connection":
			case "content-length":
			case "content-encoding":
			case "server":
			case "transfer-encoding":
				return false;
			default:
				return true;
		}
	}


	/**
	 * Size of the copy buffer used to stream an origin body to the client. Fixed, and large enough
	 * that a typical page moves in a handful of writes.
	 */
	private static final int STREAM_COPY_BUFFER_SIZE = 16 * 1024;

	/**
	 * Streams {@code in} to {@code out}. Neither is closed or flushed here - every caller already does
	 * that in a {@code finally}.
	 *
	 * The previous version had three problems, all of them on the path that streams a bypassed origin
	 * response to the client:
	 *
	 * <ul>
	 * <li>it called {@code out.flush()} after EVERY chunk, so a 5 MB download meant thousands of
	 *     flushes - a write syscall each, and a chunked-transfer boundary on the wire;</li>
	 * <li>the buffer started at 1 KB and DOUBLED every time a read filled it, with no ceiling -
	 *     allocating and abandoning 2 KB, 4 KB, ... up to megabytes while streaming from a fast
	 *     origin;</li>
	 * <li>it caught IOException INSIDE the loop and printed a stack trace. A client disconnect
	 *     (broken pipe) was therefore swallowed, and the loop kept pulling the whole origin body into
	 *     a socket nobody was listening to, printing a trace per kilobyte.</li>
	 * </ul>
	 *
	 * The IOException now propagates: the callers' {@code finally} blocks release the pooled origin
	 * connection, and a disconnected client should end the transfer rather than be written to 5000
	 * more times.
	 */
	public static void writeResponse(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[STREAM_COPY_BUFFER_SIZE];
		int bytesRead;
		while (-1 != (bytesRead = in.read(buffer)))
			out.write(buffer, 0, bytesRead);
	}

	/**
	 * The domain a metrics-stream subscriber is allowed to see, from its site-key header.
	 *
	 * A matching site key resolves to this node's domain; anything else resolves to DEFAULT_DOMAIN,
	 * which matches no command group - so an unauthenticated subscriber sees an empty stream.
	 */
	/**
	 * True when the request carries this node's management site key.
	 *
	 * Replaces {@code getDomainFromSiteKeyHeader()}, which answered a different question than its one
	 * caller asked. It returned the literal {@code "default-domain"} for an unauthorized request, and
	 * since that string matches no command group the metrics stream answered with 200 plus an endless
	 * run of empty frames - an authorization failure dressed up as an empty success. The stream now
	 * refuses with 401 (proposal section 14.6), and it no longer needs a domain at all: the {@code group}
	 * field of each frame comes from the metrics object that owns it.
	 */
	/**
	 * Reads the credential through {@link SiteKey#presented} - so the stream accepts
	 * {@code Authorization: Bearer <site-key>} like everything else, and the deprecated
	 * {@code x-frontcache-site-key} for as long as that is accepted anywhere.
	 *
	 * The DECISION stays here and stays stricter than {@link SiteKey#isAuthorized}: a node with no site
	 * key configured refuses the stream, where it would allow a management call. That difference is
	 * long-standing and deliberate - see the SiteKey javadoc - so unifying the header did not unify it.
	 */
	public static boolean isAuthorizedSiteKeyHeader(HttpServletRequest request) {
		String siteKey = SiteKey.presented(request);

		return FrontCacheEngine.getFrontCache().isNodeSiteKey(siteKey);
	}

	/**
	 * Apply a fallback {@link WebResponse}'s status code and headers (content type plus the
	 * non-cacheable Cache-Control/Pragma markers) to the servlet response.
	 *
	 * Used by the commands that stream the fallback body directly, so that the 503 status
	 * and no-store headers set on the fallback actually reach the client / downstream caches.
	 * Must be called before the body is written.
	 *
	 * @param servletResponse target response
	 * @param webResponse fallback response carrying status and headers
	 */
	public static void applyFallbackHeaders(HttpServletResponse servletResponse, WebResponse webResponse)
	{
		if (null == webResponse)
			return;

		servletResponse.setStatus(webResponse.getStatusCode());

		String contentType = webResponse.getHeader(FCHeaders.CONTENT_TYPE);
		if (null != contentType)
			servletResponse.setContentType(contentType);

		String cacheControl = webResponse.getHeader(FCHeaders.CACHE_CONTROL);
		if (null != cacheControl)
			servletResponse.setHeader(FCHeaders.CACHE_CONTROL, cacheControl);

		String pragma = webResponse.getHeader(FCHeaders.PRAGMA);
		if (null != pragma)
			servletResponse.setHeader(FCHeaders.PRAGMA, pragma);
	}


}
