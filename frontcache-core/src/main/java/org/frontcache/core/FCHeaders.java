package org.frontcache.core;

public class FCHeaders {

    public static final String ACCEPT_ENCODING = "accept-encoding";
    public static final String ACCEPT = "Accept";
    public static final String CONTENT_TYPE = "Content-Type";
    
    public static final String REQUEST_CLIENT_TYPE_BOT = "bot";
    public static final String REQUEST_CLIENT_TYPE_BROWSER = "browser";
    
    public static final String COMPONENT_REFRESH_TYPE_REGULAR = "regular"; // for X_FRONTCACHE_COMPONENT_REFRESH_TYPE
    public static final String COMPONENT_REFRESH_TYPE_SOFT = "soft";
    
    public static final String X_FRONTCACHE_ID = "X-frontcache.id";
    
    public static final String X_FRONTCACHE_DEBUG = "X-frontcache.debug";

    public static final String X_FRONTCACHE_SITE_KEY = "X-frontcache.site-key"; // hardcoded in 'frontcache-agent' - remember to update when needed

    public static final String X_FRONTCACHE_DYNAMIC_REQUEST = "X-frontcache.dynamic-request";
    public static final String X_FRONTCACHE_SOFT_REFRESH = "X-frontcache.soft-refresh";
    public static final String X_FRONTCACHE_ASYNC_INCLUDE = "X-frontcache.async-include";

    public static final String X_FRONTCACHE_DEBUG_CACHEABLE = "X-frontcache.debug.cacheable";
    public static final String X_FRONTCACHE_DEBUG_CACHED = "X-frontcache.debug.cached";
    public static final String X_FRONTCACHE_DEBUG_RESPONSE_TIME = "X-frontcache.debug.response-time";
    public static final String X_FRONTCACHE_DEBUG_RESPONSE_SIZE = "X-frontcache.debug.response-size";

    public static final String X_FRONTCACHE_COMPONENT = "X-frontcache.component";

    public static final String COMPONENT_TOPLEVEL = "toplevel";
    public static final String COMPONENT_INCLUDE = "include";
    public static final String COMPONENT_ASYNC_INCLUDE = "include-async";
    
    public static final String CACHE_LEVEL_L1 = "L1";
    public static final String CACHE_LEVEL_L2 = "L2";
    
    public static final String X_FRONTCACHE_COMPONENT_CACHE_LEVEL = "X-frontcache.component.cache-level"; // [L1 | L2] default is L2 (if null); optional - used with some cache processors only (eg L1L2CacheProcessor)
    public static final String X_FRONTCACHE_COMPONENT_MAX_AGE = "X-frontcache.component.maxage";
    public static final String X_FRONTCACHE_COMPONENT_REFRESH_TYPE = "X-frontcache.component.refresh";
    public static final String X_FRONTCACHE_COMPONENT_TAGS = "X-frontcache.component.tags"; // invalidation tags 
    public static final String X_FRONTCACHE_REQUEST_ID = "X-frontcache.request-id";
    public static final String X_FRONTCACHE_CLIENT_IP = "X-frontcache.client-ip";
    
    public static final String COMPONENT_TAGS_SEPARATOR = "\\|"; // to split invalidation tags e.g. apple|banana|orange 

}
