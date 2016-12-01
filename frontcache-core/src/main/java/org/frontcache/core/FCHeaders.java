package org.frontcache.core;

public class FCHeaders {

    public static final String ACCEPT_ENCODING = "accept-encoding";
    public static final String ACCEPT = "Accept";
    public static final String CONTENT_TYPE = "Content-Type";
    
    public static final String REQUEST_CLIENT_TYPE_BOT = "bot";
    public static final String REQUEST_CLIENT_TYPE_BROWSER = "browser";
    
    public static final String X_FRONTCACHE_ID = "X-frontcache.id";
    
    public static final String X_FRONTCACHE_DEBUG = "X-frontcache.debug";

    public static final String X_FRONTCACHE_DEBUG_CACHEABLE = "X-frontcache.debug.cacheable";
    public static final String X_FRONTCACHE_DEBUG_CACHED = "X-frontcache.debug.cached";
    public static final String X_FRONTCACHE_DEBUG_RESPONSE_TIME = "X-frontcache.debug.response-time";
    public static final String X_FRONTCACHE_DEBUG_RESPONSE_SIZE = "X-frontcache.debug.response-size";

    public static final String X_FRONTCACHE_COMPONENT = "X-frontcache.component";

    public static final String X_FRONTCACHE_COMPONENT_TOPLEVEL = "toplevel";
    public static final String X_FRONTCACHE_COMPONENT_INCLUDE = "include";
    
    
    public static final String X_FRONTCACHE_COMPONENT_MAX_AGE = "X-frontcache.component.maxage";
    public static final String X_FRONTCACHE_COMPONENT_TAGS = "X-frontcache.component.tags"; // invalidation tags 
    public static final String X_FRONTCACHE_REQUEST_ID = "X-frontcache.request-id";
    public static final String X_FRONTCACHE_CLIENT_IP = "X-frontcache.client-ip";
    
    public static final String COMPONENT_TAGS_SEPARATOR = "\\|"; // to split invalidation tags e.g. apple|banana|orange 

}
