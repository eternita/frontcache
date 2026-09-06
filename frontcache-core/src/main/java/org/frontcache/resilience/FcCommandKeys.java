/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The command keys, in one place.
 *
 * A command key is not an internal name. It owns a circuit breaker, a bulkhead and a time limiter;
 * it is the {@code <key>} in {@code resilience.command.<key>.*}; it is the {@code name} field of a
 * dashboard frame; and it is the {@code command} label on every {@code frontcache_*} metric series.
 * They were string literals scattered across the {@code FC_*} classes until 2.9.0 renamed four of
 * them and found them by grep.
 *
 * <h2>What each one covers</h2>
 *
 * <ul>
 * <li>{@code Input-Request} - a request from a client. The dashboard's headline traffic figure.</li>
 * <li>{@code Include-Request} - an include arriving back through this node's own front door.</li>
 * <li>{@code Cache-Hits} - a cache lookup. Every lookup, hit or miss, hence the surrounding command.</li>
 * <li>{@code Cache-Miss} - the origin call a cache miss makes.</li>
 * <li>{@code Cache-Bypass} - the origin call a request that never consults the cache makes.</li>
 * </ul>
 *
 * <h2>{@code Cache-Miss} is two pre-2.9.0 keys combined</h2>
 *
 * {@code Cache-Origin-http} (a socket to the origin) and {@code Cache-Origin-filter} (the filter
 * chain of the app Frontcache is installed in) named the TRANSPORT, which is a property of how the
 * node is deployed rather than of what the command does - both are "the origin call behind a cache
 * miss", and an operator tuning one always wanted the same number in the other. They now share one
 * key, and therefore one breaker, one bulkhead and one row on the dashboard.
 *
 * <b>That is a behaviour change on a filter-mode node, and only there.</b> A standalone node only
 * ever used the http one. A filter-mode node uses the filter chain for the page's own origin call
 * and HTTP for include fetches ({@code fetchOverHttp} is always HTTP, never the chain), so it had
 * two independent breakers over the same origin and now has one. Sharing is the more correct answer
 * - it is one origin, and a breaker that sees all of its failures opens on evidence the split
 * version threw away - but the concurrency permits are now shared too.
 *
 * <h2>The old names still configure the new ones</h2>
 *
 * {@link #LEGACY_COMMAND_KEYS} maps each new key to the pre-2.9.0 key it inherits settings from when
 * a {@code resilience.properties} says nothing about the new name. Renaming a command key without
 * this would silently reset that command to the built-in defaults - {@code timeout=1000},
 * {@code maxConcurrentRequests=10} - on every node whose file predates the rename. For an origin
 * call tuned to 10 or 20 seconds, that is not a metrics change, it is an outage. See
 * {@code FcResilienceConfig}, which walks the chain.
 */
public class FcCommandKeys {

	/** A request from a client - what an operator reads as "how much traffic is this node taking". */
	public static final String INPUT_REQUEST = "Input-Request";

	/** A Frontcache include arriving back through this node's own front door. */
	public static final String INCLUDE_REQUEST = "Include-Request";

	/** A cache lookup - every lookup, whether it hits or misses. */
	public static final String CACHE_HITS = "Cache-Hits";

	/** The origin call behind a cache miss, whichever transport reaches the origin. */
	public static final String CACHE_MISS = "Cache-Miss";

	/** The origin call for a request that bypasses the cache entirely. */
	public static final String CACHE_BYPASS = "Cache-Bypass";

	/**
	 * The thread pool every THREAD-isolated origin call runs on - {@link #CACHE_MISS} and
	 * {@link #CACHE_BYPASS} both.
	 *
	 * Named after what the pool is for rather than after either of the commands that use it: "calls to
	 * the origin" is the property those two share, and it is what an operator is sizing when they set
	 * {@code coreSize}. Called {@code OriginHitsPool} through 2.8, after the command key that became
	 * {@code Cache-Bypass} - which was the RARER of the two paths, so the old name pointed at the one
	 * that does not fill the pool.
	 */
	public static final String ORIGIN_POOL = "OriginPool";

	/**
	 * New key -> the pre-2.9.0 key whose settings it falls back on.
	 *
	 * A LinkedHashMap because the order is the order these are reported in, and a stable order makes
	 * the startup line and any test over it deterministic.
	 *
	 * {@code Cache-Miss} inherits from {@code Cache-Origin-http} rather than {@code Cache-Origin-filter}
	 * because the http one is the key every deployment mode used - a filter-mode node fetches its
	 * includes over HTTP. A node that deliberately tuned the two differently is the one case this
	 * cannot carry over, and it has to set {@code resilience.command.Cache-Miss.*} explicitly.
	 */
	public static final Map<String, String> LEGACY_COMMAND_KEYS;
	static {
		Map<String, String> m = new LinkedHashMap<String, String>();
		m.put(INPUT_REQUEST, "Input-Requests");
		m.put(INCLUDE_REQUEST, "Include-Requests");
		m.put(CACHE_MISS, "Cache-Origin-http");
		m.put(CACHE_BYPASS, "Origin-Hits");
		LEGACY_COMMAND_KEYS = Collections.unmodifiableMap(m);
	}

	/**
	 * New thread-pool key -> the pre-2.9.0 key whose settings it falls back on.
	 *
	 * The same reasoning as {@link #LEGACY_COMMAND_KEYS}, and if anything a sharper failure. A pool
	 * key that resolves to nothing does not stop at a merely different number: {@code coreSize} falls
	 * to {@code threadpool.default.coreSize}, and on a file that never set one, to the built-in 10.
	 * A node whose operator had tuned {@code OriginHitsPool.coreSize=200} would come up capping every
	 * origin call at ten threads, having changed nothing.
	 */
	public static final Map<String, String> LEGACY_THREAD_POOL_KEYS;
	static {
		Map<String, String> m = new LinkedHashMap<String, String>();
		m.put(ORIGIN_POOL, "OriginHitsPool");
		LEGACY_THREAD_POOL_KEYS = Collections.unmodifiableMap(m);
	}

	private FcCommandKeys() { }

}
