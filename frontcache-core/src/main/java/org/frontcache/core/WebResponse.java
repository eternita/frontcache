/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.frontcache.cache.CacheProcessor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 *
 * Container for web response. Usually it's text response for GET method.
 *
 *
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class WebResponse implements Serializable {


	/**
	 *
	 */
	private static final long serialVersionUID = 7L;

	private int statusCode = -1; // for redirects

	private String url;


	private byte[] content;

	/**
	 * Some headers, such as Accept-Language can be sent by clients as several headers each with a different value rather than sending the header as a comma separated list
	 */
	private Map<String, List<String>> headers;

	private final Set<String> tags = new HashSet<String>();

	// -1 cache forever
	// 0 never cache
	// 123456789 expiration time in ms

	// empty map -> never cache
	private Map<String, Long> expireTimeMap = new HashMap<String, Long>();

	private String refreshType = null; // null is default (regular) [regular | soft]

	private String cacheLevel = null; // null is default (L2) [L1 | L2]


	public WebResponse() { // for JSON converter
		this("dummy", null, null, null);
	}
	/**
	 * some responses has no body (e.g. response for redirect)
	 *
	 * maxAgeStr = null so, content is not subject to cache (maxAge = 0/no_cache)
	 *
	 * @param url
	 */
	public WebResponse(String url) {
		this(url, null, null, null);
	}

	/**
	 *
	 * maxAgeStr = null so, content is not subject to cache (maxAge = 0/no_cache)
	 *
	 * @param url
	 * @param content
	 */
	public WebResponse(String url, byte[] content) {
		this(url, content, null, null);
	}

	public WebResponse(String url, byte[] content, String maxAgeStr, String refreshType) {
		super();
		this.url = url;
		this.headers = new HashMap<String, List<String>>();
		this.content = content;
		setExpireTime(maxAgeStr);
		this.refreshType = refreshType;
	}

	/**
	 * Builds an instance WITHOUT parsing a maxAge - for {@link #copy()}, which replaces the whole
	 * expireTimeMap on the next line anyway.
	 *
	 * The marker argument exists only to keep this distinct from {@link #WebResponse()}, which does
	 * parse (via {@code this("dummy", null, null, null)}) and so cannot be reused here.
	 */
	private WebResponse(boolean skipExpireTimeParsing) {
		super();
		this.headers = new HashMap<String, List<String>>();
	}

	public int getStatusCode() {
		return statusCode;
	}



	public void setStatusCode(int statusCode) {
		this.statusCode = statusCode;
	}



	public String getUrl() {
		return url;
	}



	public void setUrl(String url) {
		this.url = url;
	}



	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	public String getHeader(String name) {

		Collection<String> headerValues = headers.get(name);

		if (null != headerValues && !headerValues.isEmpty())
			return headerValues.iterator().next();

		return null;
	}

	public boolean isText()
	{
		String contentType = getHeader(FCHeaders.CONTENT_TYPE);
		if (null != contentType && -1 < contentType.indexOf("text"))
			return true;

		return false;
	}

	/**
	 *
	 * @param headers
	 */
	public void setHeaders(Map<String, List<String>> headers) {
		this.headers = headers;

		return;
	}

	/**
	 *
	 * @return
	 */
	public Set<String> getTags() {
		return tags;
	}


	/**
	 *
	 * @param tags
	 */
	public void setTags(Set<String> tags) {
		this.tags.addAll(tags);
	}

	/**
	 *
	 * @param tags
	 */
	public void addTags(Collection<String> tags) {
		this.tags.addAll(tags);
	}

	/**
	 *
	 * @return
	 */
	public byte[] getContent() {
		return content;
	}

	/**
	 *
	 * @param content
	 */
	public void setContent(byte[] content) {
		this.content = content;
	}

	/**
	 * Check if response is cacheable
	 *
	 * @return
	 */
	public boolean isCacheable()
	{
		if (expireTimeMap.isEmpty())
			return false; // do not cache marker

		if (null == headers)
			return false; // no header

		if (null == content)
			return false;  // no data

		String contentType = getHeader(FCHeaders.CONTENT_TYPE);
		if (null == contentType || -1 == contentType.indexOf("text"))
			return false;  // response data is not text

		return true;
	}

	/**
	 *
	 *
	 * @param expiteTimeStr - maxAge="[bot|guest:]30d"
	 */
	private void setExpireTime(String maxAgeStr) {

		String maxAgeClientType = null; // bot and guest
		String maxAgeTime = "0"; // default no cache

		if (null != maxAgeStr)
		{
			if (-1 < maxAgeStr.indexOf(":"))
			{
				maxAgeClientType = maxAgeStr.substring(0, maxAgeStr.indexOf(":"));
				maxAgeTime = maxAgeStr.substring(maxAgeStr.indexOf(":") + 1);

				if ("".equals(maxAgeTime))
					maxAgeTime = "0";

			} else {
				maxAgeTime = maxAgeStr;
			}
		}



		long cacheMaxAgeSecLong = 0; // default - no cache
		try
		{
			cacheMaxAgeSecLong = FCUtils.maxAgeStr2Int(maxAgeTime);
		} catch (Exception ex) {
			ex.printStackTrace();
		}


		if (CacheProcessor.CACHE_FOREVER == cacheMaxAgeSecLong)
			cacheMaxAgeSecLong = CacheProcessor.CACHE_FOREVER;
		else if (CacheProcessor.NO_CACHE == cacheMaxAgeSecLong)
			cacheMaxAgeSecLong = CacheProcessor.NO_CACHE;
		else
			cacheMaxAgeSecLong = System.currentTimeMillis() + 1000 * cacheMaxAgeSecLong;

		if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(maxAgeClientType))
		{
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, cacheMaxAgeSecLong);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, CacheProcessor.NO_CACHE);

		} else if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(maxAgeClientType)) {

			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, CacheProcessor.NO_CACHE);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, cacheMaxAgeSecLong);
		} else {
			// default
			// TODO: something wrong - log it
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, cacheMaxAgeSecLong);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, cacheMaxAgeSecLong);
		}

		return;
	}


	/**
	 * content length in bytes
	 *
	 * @return
	 */
	public long getContentLenth()
	{
		if (null != content)
			return content.length;

		return -1;
	}

	public void addHeader(String name, String value)
	{
		List<String> values = headers.get(name);
		if(null == values)
		{
			values = new ArrayList<String>();
			headers.put(name, values);
		}
		values.add(value);
	}

    /**
     * A shallow copy: the content {@code byte[]} is SHARED, not cloned. That is deliberate and is what
     * keeps a cache hit from duplicating the whole page - the copy exists so that
     * {@code putToCache}'s in-place header strip, and the response headers a caller adds, do not reach
     * the instance held in the cache.
     *
     * Two things here were wrong rather than merely slow, and both are fixed:
     *
     * <ul>
     * <li>the header map came back as a plain {@code HashMap}. Every map built by
     *     {@code FCUtils.revertHeaders} is a {@code TreeMap(String.CASE_INSENSITIVE_ORDER)}, because
     *     header names are case-insensitive (RFC 7230) and intermediaries re-case them - so copying
     *     into a HashMap silently made the copy case-SENSITIVE, and a re-cased {@code Content-Type}
     *     stopped resolving on it. The comparator is preserved now;</li>
     * <li>it routed through {@code new WebResponse(url, content)}, whose constructor calls
     *     {@code setExpireTime(null)} - a trim, four {@code endsWith} checks and an
     *     {@code Integer.parseInt} in {@link FCUtils#maxAgeStr2Int} - to populate an expireTimeMap that
     *     the very next line overwrote. This runs on every cache hit.</li>
     * </ul>
     */
    public WebResponse copy() {
    	WebResponse copy = new WebResponse(true); // no maxAge parsing - the map below replaces it wholesale
    	copy.url = this.url;
    	copy.content = this.content;
    	copy.expireTimeMap.putAll(this.getExpireTimeMap());
    	copy.statusCode = this.statusCode;
    	copy.refreshType = this.refreshType;
    	copy.cacheLevel = this.cacheLevel;
    	copy.tags.addAll(this.tags);

    	if (null != headers)
    	{
    		// same comparator as the source, so a case-insensitive map stays case-insensitive
    		copy.headers = newHeaderMapLike(this.headers);

    		for (Map.Entry<String, List<String>> header : this.headers.entrySet())
    			copy.headers.put(header.getKey(), new ArrayList<String>(header.getValue()));
    	}

        return copy;
    }

    /**
     * An empty header map with the same ordering/equality semantics as {@code source} - which for
     * everything that came through {@code FCUtils.revertHeaders} means case-insensitive.
     */
    private static Map<String, List<String>> newHeaderMapLike(Map<String, List<String>> source) {
    	if (source instanceof SortedMap)
    	{
    		Comparator<? super String> comparator = ((SortedMap<String, List<String>>) source).comparator();
    		if (null != comparator)
    			return new TreeMap<String, List<String>>(comparator);
    	}
    	return new HashMap<String, List<String>>();
    }

	/**
	 * Effective expiration timestamp (epoch millis) of this response across all
	 * client types. Used for time-based purging of stale entries.
	 *
	 * An entry that is still serveable to bots until T1 and to guests until T2
	 * is fully expired only after max(T1, T2), so the latest expiration wins.
	 *
	 * @return CACHE_FOREVER (-1) if the response never expires;
	 *         NO_CACHE (0) if it is not cacheable for any client type;
	 *         otherwise the latest expiration timestamp among client types.
	 */
	public long getExpireTimeMillis() {
		long latest = CacheProcessor.NO_CACHE; // 0
		for (Long expireTime : expireTimeMap.values()) {
			if (null == expireTime)
				continue;
			if (CacheProcessor.CACHE_FOREVER == expireTime)
				return CacheProcessor.CACHE_FOREVER;
			if (expireTime > latest)
				latest = expireTime;
		}
		return latest;
	}

	public Map<String, Long> getExpireTimeMap() {
		return expireTimeMap;
	}

	public void setExpireTimeMap(Map<String, Long> expireTimeMap) {
		this.expireTimeMap = expireTimeMap;
	}

	public String getRefreshType() {
		return refreshType;
	}

	// for JSON convenience only
	public void setRefreshType(String refreshType) {
		this.refreshType = refreshType;
	}
	public String getCacheLevel() {
		return cacheLevel;
	}
	public void setCacheLevel(String cacheLevel) {
		this.cacheLevel = cacheLevel;
	}

}
