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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	// static, so neither Java serialization nor the Gson/Jackson mapping of this class sees it
	private static final Logger logger = LoggerFactory.getLogger(WebResponse.class);

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
	 * Parses {@code maxAge="[bot|guest:]30d"} - or a comma-separated list of them,
	 * {@code maxAge="bot:30d, guest:1d"} - into the per-client-type expireTimeMap.
	 *
	 * <h2>The rule</h2>
	 *
	 * There is one rule and the old single-value forms fall out of it: <b>a client type absent from
	 * the value is NO_CACHE for that type.</b> {@code bot:30d} is not a special form, it is a
	 * one-entry list; the map starts as NO_CACHE for both types and each entry overwrites its own
	 * key. So the "a prefix means NOT cacheable for the other one" behaviour that predates the list
	 * needs no branch of its own - and cannot diverge between the {@code bot:} and {@code guest:}
	 * directions the way it once did (see below).
	 *
	 * A value with no prefix at all is the exception, and stays the common case: one TTL, both types.
	 *
	 * <h2>Two live TTLs share ONE entry</h2>
	 *
	 * The cache key has no client-type dimension, so {@code bot:30d, guest:1d} is one cached copy
	 * carrying two expiration stamps, and whichever client type re-renders it resets both. The
	 * shortest TTL therefore sets how often the origin renders; the longer ones say which client
	 * types are allowed never to trigger a render. On a page with steady human traffic crawlers ride
	 * along on the copy the humans paid for; on a long-tail page with no human traffic - the SEO case
	 * the split exists for - nothing resets the entry and it genuinely ages to the full bot TTL.
	 *
	 * The eviction predicate in {@code CacheProcessorBase} / {@code IncludeProcessorBase} is the
	 * other half of that: with two live TTLs, one type's expiry must not delete the copy still valid
	 * for the other.
	 *
	 * <h2>Malformed input - three cases, three behaviours</h2>
	 *
	 * <ul>
	 * <li><b>Duplicate client type</b> ({@code bot:30d, bot:1h}) - warn, first wins, the idiom
	 *     {@code guard-rules.conf} and {@code bots.conf} already use.</li>
	 * <li><b>Unknown client type inside a list</b> ({@code bot:30d, browser:1d}) - warn and ignore
	 *     that entry, leaving the type it meant at NO_CACHE. Deliberately NOT the single-value
	 *     behaviour: in a list the author has unambiguously asked for a split, so falling through to
	 *     "cache for both" would be the one outcome they certainly did not want.</li>
	 * <li><b>Unknown prefix as the whole value</b> ({@code browser:30d}) - warn, cache for both.
	 *     Unchanged, and it must stay unchanged: it is what an un-updated pre-2.9.0 page produces,
	 *     and narrowing it would take pages out of cache on upgrade.</li>
	 * </ul>
	 *
	 * <h2>The defect this shape of code once hid</h2>
	 *
	 * The {@code guest:} branch used to test {@code REQUEST_CLIENT_TYPE_BOT} - the same constant as
	 * the branch above it - so it was unreachable and a {@code guest:} prefix silently fell through
	 * to the no-prefix default, caching for BOTH client types. Nothing caught it because every test
	 * fixture in the repo uses {@code bot:}. An unrecognized prefix warns rather than being treated
	 * as no prefix at all, which is what made this shape of mistake invisible.
	 *
	 * @param maxAgeStr the raw x-frontcache-component-maxage value, or null
	 */
	private void setExpireTime(String maxAgeStr) {

		// Not cacheable for either type until an entry says otherwise. This default IS the
		// "absent client type is not cached" rule.
		this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, CacheProcessor.NO_CACHE);
		this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, CacheProcessor.NO_CACHE);

		if (null == maxAgeStr)
			return; // no header - not subject to cache

		String value = maxAgeStr.trim();

		if (-1 == value.indexOf(","))
			setSingleValueExpireTime(maxAgeStr, value);
		else
			setListExpireTime(maxAgeStr, value);

		return;
	}

	/**
	 * The pre-list form: a bare TTL, or one {@code clientType:ttl} pair.
	 *
	 * @param maxAgeStr the raw value, for messages
	 * @param value     the same, trimmed
	 */
	private void setSingleValueExpireTime(String maxAgeStr, String value) {

		String maxAgeClientType = null; // bot | guest
		String maxAgeTime = value;

		int idx = value.indexOf(":");
		if (-1 < idx)
		{
			maxAgeClientType = value.substring(0, idx).trim();
			maxAgeTime = value.substring(idx + 1).trim();

			if ("".equals(maxAgeTime))
				maxAgeTime = "0";
		}

		long expireTime = toExpireTimeMillis(maxAgeTime);

		if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(maxAgeClientType))
		{
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, expireTime);
			// guest stays NO_CACHE - the default set by setExpireTime()

		} else if (FCHeaders.REQUEST_CLIENT_TYPE_GUEST.equals(maxAgeClientType)) {

			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, expireTime);
			// bot stays NO_CACHE

		} else {
			// No prefix - the common case, same TTL for both. A prefix we do not recognize lands here
			// too, and that is a config error worth naming: the caller asked for a split and is
			// getting the opposite of one. 'browser:' is the likely spelling, renamed to 'guest:'.
			if (null != maxAgeClientType)
				logger.warn("Unknown client type '" + maxAgeClientType + "' in maxAge=\"" + maxAgeStr
						+ "\" for " + url + " - expected '" + FCHeaders.REQUEST_CLIENT_TYPE_BOT + "' or '"
						+ FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "'. Caching for BOTH client types.");

			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, expireTime);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, expireTime);
		}
	}

	/**
	 * {@code bot:30d, guest:1d} - one TTL per client type, both live.
	 *
	 * @param maxAgeStr the raw value, for messages
	 * @param value     the same, trimmed
	 */
	private void setListExpireTime(String maxAgeStr, String value) {

		Set<String> seenClientTypes = new HashSet<String>();

		for (String rawEntry : value.split(","))
		{
			String entry = rawEntry.trim();

			int idx = entry.indexOf(":");
			if (-1 == idx)
			{
				// includes the empty segment of 'bot:30d,,guest:1d'
				logger.warn("Malformed entry '" + entry + "' in maxAge=\"" + maxAgeStr + "\" for " + url
						+ " - a list entry must be '" + FCHeaders.REQUEST_CLIENT_TYPE_BOT + ":<ttl>' or '"
						+ FCHeaders.REQUEST_CLIENT_TYPE_GUEST + ":<ttl>'. Entry ignored.");
				continue;
			}

			String clientType = entry.substring(0, idx).trim();
			String maxAgeTime = entry.substring(idx + 1).trim();

			if ("".equals(maxAgeTime))
				maxAgeTime = "0";

			if (!FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(clientType)
					&& !FCHeaders.REQUEST_CLIENT_TYPE_GUEST.equals(clientType))
			{
				// NOT the single-value behaviour of caching for both: a list is an explicit request
				// for a split, so 'cache for everyone' is the one outcome the author did not ask for.
				logger.warn("Unknown client type '" + clientType + "' in maxAge=\"" + maxAgeStr + "\" for "
						+ url + " - expected '" + FCHeaders.REQUEST_CLIENT_TYPE_BOT + "' or '"
						+ FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "'. Entry ignored, that client type is NOT cached.");
				continue;
			}

			if (!seenClientTypes.add(clientType))
			{
				// first wins - as in guard-rules.conf and bots.conf, so the intuition transfers
				logger.warn("Duplicate client type '" + clientType + "' in maxAge=\"" + maxAgeStr + "\" for "
						+ url + " - the first entry wins, '" + entry + "' ignored.");
				continue;
			}

			this.expireTimeMap.put(clientType, toExpireTimeMillis(maxAgeTime));
		}
	}

	/**
	 * A TTL string ({@code 30d}, {@code forever}, {@code 0}) as an absolute expiration timestamp.
	 *
	 * An unparseable TTL is NO_CACHE for that client type - {@link FCUtils#maxAgeStr2Int} warns and
	 * returns it - so {@code bot:30d, guest:banana} caches for bots and not for guests.
	 */
	private static long toExpireTimeMillis(String maxAgeTime) {

		long cacheMaxAgeSecLong = FCUtils.maxAgeStr2Int(maxAgeTime);

		if (CacheProcessor.CACHE_FOREVER == cacheMaxAgeSecLong)
			return CacheProcessor.CACHE_FOREVER;

		if (CacheProcessor.NO_CACHE == cacheMaxAgeSecLong)
			return CacheProcessor.NO_CACHE;

		return System.currentTimeMillis() + 1000 * cacheMaxAgeSecLong;
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
