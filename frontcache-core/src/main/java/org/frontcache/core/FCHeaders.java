/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

public class FCHeaders {

    public static final String ACCEPT_ENCODING = "accept-encoding";
    public static final String ACCEPT = "Accept";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String RETRY_AFTER = "Retry-After";
    public static final String PRAGMA = "Pragma";

    public static final String REQUEST_CLIENT_TYPE_BOT = "bot";
    public static final String REQUEST_CLIENT_TYPE_GUEST = "guest";

    public static final String COMPONENT_REFRESH_TYPE_REGULAR = "regular"; // for X_FRONTCACHE_COMPONENT_REFRESH_TYPE
    public static final String COMPONENT_REFRESH_TYPE_SOFT = "soft";

    // All frontcache HTTP header names are hyphenated (no dots). Dotted request header names are
    // stripped by some intermediaries (notably Cloudflare) on a CDN hop between two frontcache
    // nodes, which would silently drop the "this is a frontcache request" signal. Keep every
    // frontcache header name dot-free so the naming is consistent and survives any intermediary.
    public static final String X_FRONTCACHE_ID = "x-frontcache-id";

    /**
     * The pre-2.7 way to send the management API key. Nothing sends it any more - every Frontcache
     * caller now uses {@code Authorization: Bearer <api-key>} - but nodes still ACCEPT it, because an
     * embedded frontcache-agent, a separately deployed console or an older peer node may be older than
     * the node it is calling. See {@link ApiKey}. Remove once every supported caller is 2.7 or later.
     *
     * The header name itself keeps "site-key": it is a fixed string on the wire that older callers
     * send, so renaming it would simply stop accepting them, which is the whole reason it still exists.
     *
     * @deprecated send {@code Authorization: Bearer <api-key>} instead
     */
    @Deprecated
    public static final String X_FRONTCACHE_SITE_KEY = "x-frontcache-site-key";

    public static final String X_FRONTCACHE_FALLBACK_IS_USED = "x-frontcache-fallback-is-used";

    public static final String X_FRONTCACHE_DYNAMIC_REQUEST = "x-frontcache-dynamic-request";
    public static final String X_FRONTCACHE_SOFT_REFRESH = "x-frontcache-soft-refresh";
    public static final String X_FRONTCACHE_ASYNC_INCLUDE = "x-frontcache-async-include";

    public static final String X_FRONTCACHE_TRACE = "x-frontcache-trace"; // request

    public static final String X_FRONTCACHE_TRACE_REQUEST = "x-frontcache-trace-request"; // response (a per-include-level numeric suffix is appended at runtime)
    public static final String X_FRONTCACHE_INCLUDE_LEVEL = "x-frontcache-include-level"; // response

    public static final String X_FRONTCACHE_COMPONENT = "x-frontcache-component";

    public static final String COMPONENT_TOPLEVEL = "toplevel";
    public static final String COMPONENT_INCLUDE = "include";
    public static final String COMPONENT_ASYNC_INCLUDE = "include-async";

    /**
     * A sync include that was resolved as one member of a combined origin call
     * (docs/archive/combine-reduce-proposal.md). Its own line in the trace headers carries the BATCH's
     * elapsed time, not a time of its own - so N members of one batch report N identical durations
     * that do not sum to the page time. This marker is what tells a reader of
     * front-cache.log-to-headers why.
     */
    public static final String COMPONENT_COMBINED_INCLUDE = "include-combined";

    public static final String CACHE_LEVEL_L1 = "L1";
    public static final String CACHE_LEVEL_L2 = "L2";

    public static final String X_FRONTCACHE_COMPONENT_CACHE_LEVEL = "x-frontcache-component-cache-level"; // [L1 | L2] default is L2 (if null); optional - used with some cache processors only (eg L1L2CacheProcessor)
    public static final String X_FRONTCACHE_COMPONENT_MAX_AGE = "x-frontcache-component-maxage";
    public static final String X_FRONTCACHE_COMPONENT_REFRESH_TYPE = "x-frontcache-component-refresh";
    public static final String X_FRONTCACHE_COMPONENT_TAGS = "x-frontcache-component-tags"; // invalidation tags
    public static final String X_FRONTCACHE_REQUEST_ID = "x-frontcache-request-id";
    public static final String X_FRONTCACHE_CLIENT_IP = "x-frontcache-client-ip";

    /**
     * The client type ({@link #REQUEST_CLIENT_TYPE_BOT} / {@link #REQUEST_CLIENT_TYPE_GUEST}) the
     * node serving the PAGE decided, carried to an {@code <fc:include>} fetch so the fragment is
     * classified the same way as the page holding it.
     *
     * Without it, a re-entering include is classified on its own: the User-Agent survives the hop, so
     * a UA-only bots.conf agrees by accident, but a client-ip: rule sees the sibling node's address
     * and a cookie: rule may see no cookies at all (an async include is resolved after the servlet
     * request is recycled). The page then reads one branch of a WebResponse's expireTimeMap and its
     * fragments read the other, which shows up as fragments quietly no longer being cached.
     *
     * <b>Honoured ONLY from a peer in {@code front-cache.client-ip.trusted-proxies}, and only on a
     * request that arrived as an include.</b> Same rule as {@link #X_FRONTCACHE_CLIENT_IP}, and the
     * same reason: {@code FCUtils.isIncludedHeader} forwards everything it does not blacklist, so
     * without the check this would be a classify-yourself header. It is blacklisted there too, so a
     * value a client sent is dropped rather than passed on.
     */
    public static final String X_FRONTCACHE_CLIENT_TYPE = "x-frontcache-client-type";

    /**
     * The cache warmer's credential on a warm request - see {@code org.frontcache.warmer.WarmRequest}.
     *
     * Every header whose name starts with this one is dropped by {@code FCUtils.isIncludedHeader}, so
     * none of them reaches the origin. That drop is load-bearing: the token in the origin app's
     * access log would let whoever reads that log skip the guard rules and force refreshes.
     */
    public static final String X_FRONTCACHE_WARM = "x-frontcache-warm";

    /** {@code fill} | {@code refresh} - honoured only beside a valid {@link #X_FRONTCACHE_WARM}. */
    public static final String X_FRONTCACHE_WARM_MODE = "x-frontcache-warm-mode";

    /** {@code bot} | {@code guest} - honoured only beside a valid {@link #X_FRONTCACHE_WARM}. */
    public static final String X_FRONTCACHE_WARM_CLIENT_TYPE = "x-frontcache-warm-client-type";

    /**
     * Response header on a warm request only: {@code stored} | {@code fresh} | {@code not-cacheable}
     * | {@code fallback}. Without it the warmer sees a status code, and "200, stored" and "200, not
     * cacheable" look the same.
     */
    public static final String X_FRONTCACHE_WARM_RESULT = "x-frontcache-warm-result";

    /**
     * Response header on a warm request only: the cache key the node built for it. The warmer
     * compares it with its key base, because the key's scheme comes from the container's view of
     * {@code X-Forwarded-Proto} and a container that ignores that header would otherwise have the
     * run store every entry under a key no visitor's request produces - and report them stored.
     */
    public static final String X_FRONTCACHE_WARM_KEY = "x-frontcache-warm-key";

    /**
     * The chain's shared secret, on every origin call a node with {@code front-cache.edge-token}
     * makes - see {@code org.frontcache.core.EdgeToken}. Read by the {@code edge-token} guard
     * predicate on the node that receives it.
     *
     * Dropped by {@code FCUtils.isIncludedHeader}, like {@link #X_FRONTCACHE_CLIENT_TYPE}: a node
     * sends its own value or none, so a client's copy is never relayed onward by us.
     */
    public static final String X_FRONTCACHE_EDGE_TOKEN = "x-frontcache-edge-token";

    // Standard forwarding headers. X_FORWARDED_PROTO is READ (from a trusted peer only - see
    // ClientIpResolver.isTrustedPeer) to recover the scheme the client actually used, which a
    // TLS-terminating proxy in front of us has already stripped. X_FORWARDED_HOST is WRITTEN, to
    // the origin, so the app can build public absolute URLs instead of origin-host ones.
    public static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    public static final String X_FORWARDED_HOST = "X-Forwarded-Host";

    public static final String COMPONENT_TAGS_SEPARATOR = "\\|"; // to split invalidation tags e.g. apple|banana|orange

}
