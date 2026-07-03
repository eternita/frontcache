/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
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

    public static final String X_FRONTCACHE_SITE_KEY = "x-frontcache-site-key"; // hardcoded in 'frontcache-agent' - remember to update when needed

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

    public static final String CACHE_LEVEL_L1 = "L1";
    public static final String CACHE_LEVEL_L2 = "L2";

    public static final String X_FRONTCACHE_COMPONENT_CACHE_LEVEL = "x-frontcache-component-cache-level"; // [L1 | L2] default is L2 (if null); optional - used with some cache processors only (eg L1L2CacheProcessor)
    public static final String X_FRONTCACHE_COMPONENT_MAX_AGE = "x-frontcache-component-maxage";
    public static final String X_FRONTCACHE_COMPONENT_REFRESH_TYPE = "x-frontcache-component-refresh";
    public static final String X_FRONTCACHE_COMPONENT_TAGS = "x-frontcache-component-tags"; // invalidation tags
    public static final String X_FRONTCACHE_REQUEST_ID = "x-frontcache-request-id";
    public static final String X_FRONTCACHE_CLIENT_IP = "x-frontcache-client-ip";

    public static final String COMPONENT_TAGS_SEPARATOR = "\\|"; // to split invalidation tags e.g. apple|banana|orange

}
