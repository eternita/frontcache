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
    public static final String REQUEST_CLIENT_TYPE_BROWSER = "browser";

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

    // Standard forwarding headers. X_FORWARDED_PROTO is READ (from a trusted peer only - see
    // ClientIpResolver.isTrustedPeer) to recover the scheme the client actually used, which a
    // TLS-terminating proxy in front of us has already stripped. X_FORWARDED_HOST is WRITTEN, to
    // the origin, so the app can build public absolute URLs instead of origin-host ones.
    public static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    public static final String X_FORWARDED_HOST = "X-Forwarded-Host";

    public static final String COMPONENT_TAGS_SEPARATOR = "\\|"; // to split invalidation tags e.g. apple|banana|orange

}
