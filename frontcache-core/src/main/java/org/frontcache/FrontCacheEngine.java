package org.frontcache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.pool.PoolStats;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.FC_BypassCache;
import org.frontcache.hystrix.FC_Total;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorManager;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheEngine {


	private Map<String, DomainContext> domainConfigMap = new ConcurrentHashMap<String, DomainContext>(); // <DomainStr, DomainConfig>
	
	private String frontcacheHttpPort = null;

	private String frontcacheHttpsPort = null;
	
	private String fcHostId = null;	// used to determine which front cache processed request (forwarded by GEO Load Balancer e.g. route53 AWS)
	
    private boolean logToHeadersConfig = false; // log requests/includes performance stat to HTTP headers
	
	public final static String DEFAULT_FRONTCACHE_HOST_NAME_VALUE = "undefined-front-cache-host";
	
	private static int fcConnectionsMaxTotal = 200;
	
	private static int fcConnectionsMaxPerRoute = 20;

	private IncludeProcessor includeProcessor = null;
	
	private CacheProcessor cacheProcessor = null; 

	protected Logger logger = null;  
	
	private final Timer connectionManagerTimer = new Timer("FrontCacheEngine.connectionManagerTimer", true);

	private PoolingHttpClientConnectionManager connectionManager;
	
	private CloseableHttpClient httpClient;
	
	public static boolean debugComments = false; // if true - appends debug comments (for includes) to output 
	
	private static FrontCacheEngine instance;
	
    private static final String INCLUDE_LEVEL_TOP_LEVEL = "0";
	
	public static FrontCacheEngine getFrontCache() {
		if (null == instance) {
//			FCConfig.init();
			
			//Logs config ->  !!! -Dlogback.configurationFile=/opt/frontcache/conf/fc-logback.xml
			
			String debugCommentsStr = FCConfig.getProperty("front-cache.debug-comments", "false");
			if ("true".equalsIgnoreCase(debugCommentsStr))
				debugComments = true;		
			
			instance = new FrontCacheEngine();
		}
		return instance;
	}
	
	public DomainContext getDomainContexBySiteKey(String siteKey)
	{
		for (DomainContext domainContext : domainConfigMap.values())
			if (null != domainContext.getSiteKey() && domainContext.getSiteKey().equals(siteKey))
				return domainContext;
				
		return null;
	}

	public static void destroy() {
		if (null != instance) {
			instance.stop();
			instance = null;
		}
	}
	
	/**
	 * 
	 */
	public static void reload() {
		
		destroy(); 
		
		getFrontCache(); // recreate
		
		return;
	}
	
	private FrontCacheEngine() {
		
		initialize();
	}
	
	// used by FrontCacheIOServlet
	public HttpClient getHttpClient()
	{
		return httpClient;
	}
	
	private void loadDomainConfigs()
	{
		
		String defaultDomain = FCConfig.getProperty("front-cache.default-domain", FCConfig.DEFAULT_DOMAIN);
		String defaultSiteKey = FCConfig.getProperty("front-cache.site-key", "");
		String defaultOriginHost = FCConfig.getProperty("front-cache.origin-host", "localhost");
		String defaultOriginHttpPort = FCConfig.getProperty("front-cache.origin-http-port", "80");
		String defaultOriginHttpsPort = FCConfig.getProperty("front-cache.origin-https-port", "443");
		
		domainConfigMap.put(FCConfig.DEFAULT_DOMAIN, 
				new DomainContext(defaultDomain, defaultSiteKey, defaultOriginHost, defaultOriginHttpPort, defaultOriginHttpsPort ));
		
		String domainList = FCConfig.getProperty("front-cache.domains");
		if (null != domainList)
		{
			for(String domain : domainList.split(","))
			{
				String siteKey = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".site-key", "");
				String originHost = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".origin-host", defaultOriginHost);
				String originHttpPort = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".origin-http-port", defaultOriginHttpPort);
				String originHttpsPort = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".origin-https-port", defaultOriginHttpsPort);
				
				domainConfigMap.put(domain, 
						new DomainContext(domain, siteKey, originHost, originHttpPort, originHttpsPort));
			}
		}
		logger.info("Loaded following origin configurations: ");

		for (String domain : domainConfigMap.keySet())
			logger.info(" -- " + domainConfigMap.get(domain));
			
		return;
	}
	
	/**
	 * 
	 * @param requestDomainName
	 * @return
	 */
	private DomainContext getDomainContext(String requestDomainName)
	{
		for (String domainSuffix : domainConfigMap.keySet())
		{
			if (requestDomainName.endsWith(domainSuffix))
			{
				if (requestDomainName.equals(domainSuffix))
					return domainConfigMap.get(domainSuffix);
				//case for abc.com & bc.com
				if (requestDomainName.endsWith("." + domainSuffix))
					return domainConfigMap.get(domainSuffix);
			}
		}
		
		// default
		return domainConfigMap.get(FCConfig.DEFAULT_DOMAIN);
	}
	
	private void initialize() {
		
		logger = LoggerFactory.getLogger(FrontCacheEngine.class);
		
		loadDomainConfigs();
		
		frontcacheHttpPort = FCConfig.getProperty("front-cache.http-port", "80");
		frontcacheHttpsPort = FCConfig.getProperty("front-cache.https-port", "443");
		
		fcHostId = FCConfig.getProperty(FCConfig.FRONTCACHE_ID_KEY);
		if (null == fcHostId)
			fcHostId = DEFAULT_FRONTCACHE_HOST_NAME_VALUE;
			
        logToHeadersConfig = "true".equals(FCConfig.getProperty("front-cache.log-to-headers", "false")) ? true : false;
		
		cacheProcessor = CacheManager.getInstance();

		includeProcessor = IncludeProcessorManager.getInstance();

		this.httpClient = newClient();

		connectionManagerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (connectionManager == null) {
					return;
				}
				connectionManager.closeExpiredConnections();
			}
		}, 30000, 5000);
		
		// log connectionManager stats
		connectionManagerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (connectionManager == null) {
					return;
				}
				PoolStats poolStats = connectionManager.getTotalStats();
				logger.info("HTTP connection manager pool stats - " + poolStats);
//				System.out.println("pool stats \n " + poolStats);
			}
		}, 1000, 60000);
		

		Thread t = new Thread(new Runnable() {

			public void run() {
				try {
					Thread.sleep(3000); // wait server is started to load fallbacks from URLs
				} catch (Exception e) {
					e.printStackTrace();
				}
				
				FallbackResolverFactory.init(httpClient);
			}
		});
		t.start();
		
		return;
	}

	private URL getOriginUrl(RequestContext context) {
		boolean isSecure = context.getRequest().isSecure();
		DomainContext domainCtx = context.getDomainContext();

		String port = isSecure ? domainCtx.getHttpsPort() : domainCtx.getHttpPort();
		
		String urlStr = makeURL(isSecure, domainCtx.getHost(), port);
		try {
			URL routeUrl = new URL(urlStr);
			return  routeUrl;
		} catch (MalformedURLException e) {
			throw new RuntimeException("Invalid front-cache.app-origin-base-url (" + domainCtx.getHost() + ")", e);
		}

	}
	
	private String makeURL(boolean isSecure, String host, String port)
	{
		StringBuffer str = new StringBuffer();
		if (isSecure)
			str.append("https");	
		else
			str.append("http");	

		str.append("://").append(host);
		if (isSecure) {
			if (!"443".equals(port))
				str.append(":").append(port);
		} else {
			if (!"80".equals(port))
				str.append(":").append(port);
		}
		
		return str.toString();
	}
	
	
	@Override
	protected void finalize() throws Throwable {
		// TODO Auto-generated method stub
		super.finalize();
		stop();
	}

	private void stop() {
		
		FCConfig.destroy();
		
		FallbackResolverFactory.destroy();
		
		connectionManagerTimer.cancel();
		
		if (null != includeProcessor)
			includeProcessor.destroy();
		
		if (null != cacheProcessor)
			cacheProcessor.destroy();
		
	}

	
	private CloseableHttpClient newClient() {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setSocketTimeout(10000)
				.setConnectTimeout(5500) // should be slightly more then hystrix timeout for http client
				.setCookieSpec(CookieSpecs.IGNORE_COOKIES)
				.build();

	    ConnectionKeepAliveStrategy keepAliveStrategy = new ConnectionKeepAliveStrategy() {
	        @Override
	        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
	            HeaderElementIterator it = new BasicHeaderElementIterator
	                (response.headerIterator(HTTP.CONN_KEEP_ALIVE));
	            while (it.hasNext()) {
	                HeaderElement he = it.nextElement();
	                String param = he.getName();
	                String value = he.getValue();
	                if (value != null && param.equalsIgnoreCase
	                   ("timeout")) {
	                    return Long.parseLong(value) * 1000;
	                }
	            }
	            return 10 * 1000;
	        }
	    };
	    
		return HttpClients.custom()
				.setConnectionManager(newConnectionManager())
				.setDefaultRequestConfig(requestConfig)
//				.setSSLHostnameVerifier(new NoopHostnameVerifier()) // for SSL do not verify certificate's host 
				.setRetryHandler(new DefaultHttpRequestRetryHandler(0, false))
				.setKeepAliveStrategy(keepAliveStrategy)
				.setRedirectStrategy(new RedirectStrategy() {
					@Override
					public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return false;
					}

					@Override
					public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return null;
					}
				})
				.build();
	}

	private PoolingHttpClientConnectionManager newConnectionManager() {
		try {
//		        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
//		        trustStore.load(new FileInputStream(keyStorePath), keyStorePassword.toCharArray());

//		        MySSLSocketFactory sf = new MySSLSocketFactory(trustStore);
//		        sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
				

//			final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
//					.register("http", PlainConnectionSocketFactory.INSTANCE)
//					.register("https", sf)
//					.build();

//			connectionManager = new PoolingHttpClientConnectionManager(registry);
			connectionManager = new PoolingHttpClientConnectionManager();
			
			connectionManager.setMaxTotal(fcConnectionsMaxTotal);
			connectionManager.setDefaultMaxPerRoute(fcConnectionsMaxPerRoute);
			
			return connectionManager;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
	
	/**
	 * Works for Servlets and ServletFilters
	 * 
	 * @param servletRequest
	 * @param servletResponse
	 * @param filterChain
	 */
    private RequestContext init(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FilterChain filterChain) {

        RequestContext context = new RequestContext();
        context.setFrontCacheId(fcHostId);
        context.setRequest(servletRequest);
        context.setResponse(servletResponse);
		String uri = FCUtils.buildRequestURI(servletRequest);
		if (-1 < uri.indexOf(";jsessionid=")) // remove JSESSIONID from URI
			uri = uri.replaceAll(";jsessionid=.*?(?=\\?|$)", "");
		
		context.setRequestURI(uri);
		
		String queryString =  servletRequest.getQueryString(); // FCUtils.getQueryString(servletRequest, context);
		queryString = ( null == queryString) ? "" : "?" + queryString; 
		
		context.setRequestQueryString(queryString);
		context.setFrontCacheHost(FCUtils.getHost(servletRequest));
		context.setFrontCacheHttpPort(frontcacheHttpPort);
		context.setFrontCacheHttpsPort(frontcacheHttpsPort);
		
		DomainContext domainContex = getDomainContext(context.getRequest().getServerName());
		context.setDomainContext(domainContex);
		
		context.setFrontCacheProtocol(FCUtils.getProtocol(servletRequest));

        context.setOriginURL(getOriginUrl(context));

        if (logToHeadersConfig || "true".equalsIgnoreCase(servletRequest.getHeader(FCHeaders.X_FRONTCACHE_TRACE)))
        {
            context.setLogToHTTPHeaders();
        }
        
        String requestId = servletRequest.getHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID);
        String requestType = FCHeaders.COMPONENT_INCLUDE;
        
        String includeLevelStr = servletRequest.getHeader(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL);
        if (null == includeLevelStr)
        	includeLevelStr = INCLUDE_LEVEL_TOP_LEVEL; // default (top level)

        context.setIncludeLevel(includeLevelStr); // top level 
        
        if (null == requestId)
        {
        	requestId = UUID.randomUUID().toString();
        	requestType = FCHeaders.COMPONENT_TOPLEVEL;
        } else {
            
        	if (null != servletRequest.getHeader(FCHeaders.X_FRONTCACHE_ASYNC_INCLUDE))
            	requestType = FCHeaders.COMPONENT_ASYNC_INCLUDE;
        	else 
        		requestType = FCHeaders.COMPONENT_INCLUDE;
        	
            context.setRequestFromFrontcache();
        }
        
        context.setRequestId(requestId);
        context.setRequestType(requestType);
        
        context.setClientType(getClientType(servletRequest, domainContex.getDomain())); // client type = bot | browser based on User-Agent Header and bots.conf
        

        if (null != filterChain)
        {
            context.setFilterChain(filterChain);
    		DomainContext origin = getDomainContext(context.getRequest().getServerName());
    		context.setFrontCacheHost(origin.getHost()); // in case of filter fc host = origin host (don't put localhost it can make issues with HTTPS and certificates for includes)
        }
		return context;
    }

	private String getClientType(HttpServletRequest request, String domain)
	{		

		String userAgent = request.getHeader("User-Agent");
		if (null != userAgent)
		{
			for (String botKeyword : FCConfig.getBotUserAgentKeywords(domain))
				if (userAgent.contains(botKeyword))
					return FCHeaders.REQUEST_CLIENT_TYPE_BOT;
		} else {
			return FCHeaders.REQUEST_CLIENT_TYPE_BOT; // no user-agent -> bot
		}
		
		return FCHeaders.REQUEST_CLIENT_TYPE_BROWSER;
	}
    
    private boolean ignoreCache(String uri, String domain)
    {
    	for (Pattern p : FCConfig.getDynamicURLPatterns(domain))
    		if (p.matcher(uri).find()) 
    			return true;
    	
    	return false;

    }
    
	public void processRequest(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FilterChain filterChain) throws Exception
	{
		RequestContext context = init(servletRequest, servletResponse, filterChain);
		new FC_Total(this, context).execute();
//		processRequestInternal();
		return;
	}
	
	/**
	 * do it outside init() because it's for cacheable requests only
	 * 
	 * @param context
	 * @return
	 */
	private void getCurrentRequestURL2Context(RequestContext context)
	{
		HttpServletRequest httpRequest = context.getRequest();
		String portStr = "";
		int port = httpRequest.getServerPort();
		if (80 == port && "http".equals(context.getFrontCacheProtocol()))
			portStr = "";
		else if (443 == port && "https".equals(context.getFrontCacheProtocol()))
			portStr = "";
		else
			portStr = ":" + port;

		String currentRequestURL = context.getFrontCacheProtocol() + "://" + context.getFrontCacheHost() + portStr + context.getRequestURI() + context.getRequestQueryString();
		context.setCurrentRequestURL(currentRequestURL);
		logger.debug("currentRequestURL: " + currentRequestURL);
		
		return;
	}
    /**
     * 
     * @throws Exception
     */
	public void processRequestInternal(RequestContext context) throws Exception
	{
		HttpServletRequest httpRequest = context.getRequest();
		String originRequestURL = getOriginUrl(context) + context.getRequestURI() + context.getRequestQueryString();
		logger.debug("originRequestURL: " + originRequestURL);
		
		boolean isSecure = ("https".equalsIgnoreCase(context.getFrontCacheProtocol())) ? true : false;
		String currentRequestBaseURL = makeURL(isSecure, context.getFrontCacheHost(), "" + httpRequest.getServerPort());
		logger.debug("currentRequestBaseURL: " + currentRequestBaseURL);
		
		boolean dynamicRequest = ("true".equals(httpRequest.getHeader(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST))) ? true : false;
				
		if (!dynamicRequest && context.isCacheableRequest() && !ignoreCache(context.getRequestURI(), context.getDomainContext().getDomain())) // GET method without jsessionid
		{
			long start = System.currentTimeMillis();

			Map<String, List<String>> requestHeaders = FCUtils.buildRequestHeaders(httpRequest);
			getCurrentRequestURL2Context(context);

			WebResponse webResponse = null;
			try
			{
				webResponse = cacheProcessor.processRequest(originRequestURL, requestHeaders, httpClient, context);
			} catch (FrontCacheException fce) {
				// content/response is not cacheable (e.g. response type is not text) 
				fce.printStackTrace();
			}

			if (null != webResponse)
			{
				// include processor
				// don't process includes if request from Frontcache (e.g. Browser -> FC -> [FC] -> Origin)
				if (!context.getRequestFromFrontcache() && null != webResponse.getContent())
				{
					// check process includes with recursion (resolve includes up to deepest level defined in includeProcessor)
					int recursionLevel = 0;
					while (includeProcessor.hasIncludes(webResponse, recursionLevel++))
					{
						// include processor return new webResponse with processed includes and merged headers
						WebResponse incWebResponse = includeProcessor.processIncludes(webResponse, currentRequestBaseURL, requestHeaders, httpClient, context, recursionLevel);
						
						// copy content only (cache setting use this (parent), headers are merged inside IncludeProcessor )
						webResponse.setContent(incWebResponse.getContent());
					}
				}
				

				if (INCLUDE_LEVEL_TOP_LEVEL.equals(context.getIncludeLevel()))
				{
					RequestLogger.logRequestToHeader(
							currentRequestBaseURL + context.getRequestURI() + context.getRequestQueryString(),
							context.getRequestType(),
							context.isToplevelCached(), // isCached 
							false, // soft refresh
							System.currentTimeMillis() - start, 
							webResponse.getContentLenth(), // lengthBytes 
							context, 
							INCLUDE_LEVEL_TOP_LEVEL); // includeLevel
				}
				
				addResponseHeaders(webResponse, context);
				writeResponse(webResponse, context);
				
				if (null != context.getHttpClientResponse())
					context.getHttpClientResponse().close();
				
				return;
			}

		}
		
		// do dynamic call to origin (all methods except GET + listed in ignore list)
		{
			long start = System.currentTimeMillis();
			boolean isRequestCacheable = false;
			boolean isCached = false;
			long lengthBytes = -1; // TODO: set/get content length from context or just keep -1 ?
			
//			forwardToOrigin();		
			new FC_BypassCache(httpClient, context).execute();
			
			RequestLogger.logRequest(originRequestURL, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);			
			addResponseHeaders(context);
			writeResponse(context);
			if (null != context.getHttpClientResponse())
				context.getHttpClientResponse().close();
		}		
		return;
	}
	
	
	private void writeResponse(RequestContext context) throws Exception {
		// there is no body to send
		if (context.getResponseBody() == null && context.getResponseDataStream() == null) {
			return;
		}
		HttpServletResponse servletResponse = context.getResponse();
		OutputStream outStream = servletResponse.getOutputStream();
		InputStream is = null;
		try {
			if (context.getResponseBody() != null) {
				String body = context.getResponseBody();
				FCUtils.writeResponse(new ByteArrayInputStream(body.getBytes()), outStream);
				return;
			}

			is = context.getResponseDataStream();
			InputStream inputStream = is;
			if (is != null) {
				FCUtils.writeResponse(inputStream, outStream);
			}

		}
		finally {
			try {
				if (is != null) {
					is.close();
				}
				outStream.flush();
				outStream.close();
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		
	}

	private void writeResponse(WebResponse webResponse, RequestContext context) throws Exception {

		// there is no body to send
		if (null == webResponse.getContent()) {
			return;
		}
		
		HttpServletResponse servletResponse = context.getResponse();
		servletResponse.setCharacterEncoding("UTF-8");
		OutputStream outStream = servletResponse.getOutputStream();
		try {
			byte[] body = webResponse.getContent();
			FCUtils.writeResponse(new ByteArrayInputStream(body), outStream);
		}
		finally {
			try {
				outStream.flush();
				outStream.close();
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		
		return;
	}
	
	private void addResponseHeaders(RequestContext context) {
		HttpServletResponse servletResponse = context.getResponse();
		Map<String, List<String>> originResponseHeaders = context.getOriginResponseHeaders();
		// process redirects
		if (null != originResponseHeaders.get("Location") && 0 < originResponseHeaders.get("Location").size())
		{
			String originLocation = originResponseHeaders.remove("Location").iterator().next();
			
			String fcLocation = FCUtils.transformRedirectURL(originLocation, context);
			originResponseHeaders.put("Location",  Arrays.asList(new String[]{fcLocation}));
		}
		
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_ID, fcHostId);
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());
		servletResponse.setStatus(context.getResponseStatusCode());
		
		if (originResponseHeaders != null) {
			for (String key : originResponseHeaders.keySet()) {
				for (String value : originResponseHeaders.get(key)) {
					servletResponse.addHeader(key, value);
				}
			}
		}

		Long contentLength = context.getOriginContentLength();
		// Only inserts Content-Length if origin provides it and origin response is not
		// gzipped
//		if (SET_CONTENT_LENGTH.get()) {
			if (contentLength != null && !context.getResponseGZipped()) {
				servletResponse.setContentLength(contentLength.intValue());
			}
//		}
	}	

	private void addResponseHeaders(WebResponse webResponse, RequestContext context) {
		HttpServletResponse servletResponse = context.getResponse();

		servletResponse.setStatus(webResponse.getStatusCode());
		
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_ID, fcHostId);
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());
		
		if (webResponse.getHeaders() != null) {
			for (String name : webResponse.getHeaders().keySet()) {
				for (String value : webResponse.getHeaders().get(name)) {
					
					if (null == servletResponse.getHeader(name)) // if header already exist (e.g. in case of WebFilter) - do not duplicate
						servletResponse.addHeader(name, value);
				}
			}
		}
	}	
	
		
}
