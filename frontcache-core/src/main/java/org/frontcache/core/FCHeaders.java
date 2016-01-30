package org.frontcache.core;

public class FCHeaders {
//    public static final String TRANSFER_ENCODING = "transfer-encoding";
//    public static final String CHUNKED = "chunked";
    public static final String ORIGIN = "Origin";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String ACCEPT_ENCODING = "accept-encoding";
    public static final String ACCEPT = "Accept";
    public static final String X_FRONTCACHE_HOST = "X-FrontCache-Host";
    public static final String CONNECTION = "Connection";
    public static final String KEEP_ALIVE = "keep-alive";
    public static final String HOST = "Host";
    
	//TODO: remove me after migration from FC filter in coinshome.net (or can be used for back compatibility)
    public static final String X_AVOID_CHN_FRONTCACHE = "X-AVOID-CHN-FRONTCACHE";
    
}
