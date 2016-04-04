package org.frontcache.core;

public class FCHeaders {

    public static final String ACCEPT_ENCODING = "accept-encoding";
    public static final String ACCEPT = "Accept";
    public static final String X_FRONTCACHE_HOST = "X-FrontCache-Host";
    
	//TODO: remove me after migration from FC filter in coinshome.net (or can be used for back compatibility)
    public static final String X_AVOID_CHN_FRONTCACHE = "X-AVOID-CHN-FRONTCACHE";

    public static final String X_FRONTCACHE_DEBUG = "X-frontcache.debug";

    public static final String X_FRONTCACHE_DEBUG_CACHEABLE = "X-frontcache.debug.cacheable";
    public static final String X_FRONTCACHE_DEBUG_CACHED = "X-frontcache.debug.cached";
    public static final String X_FRONTCACHE_DEBUG_RESPONSE_TIME = "X-frontcache.debug.response-time";
    public static final String X_FRONTCACHE_DEBUG_RESPONSE_SIZE = "X-frontcache.debug.response-size";

}
