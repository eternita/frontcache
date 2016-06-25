package org.frontcache;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.Origin;
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

	private final static String CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE = "direct-urls.conf";
	
	private List <Pattern> uriIgnorePatterns = new ArrayList<Pattern>();

	private Map<String, Origin> domainOriginMap = new ConcurrentHashMap<String, Origin>();
	
	private String frontcacheHttpPort = null;

	private String frontcacheHttpsPort = null;
	
	private String fcHostId = null;	// used to determine which front cache processed request (forwarded by GEO Load Balancer e.g. route53 AWS)
	
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
	
	public static FrontCacheEngine getFrontCache() {
		if (null == instance) {
			FCConfig.init();
			
			//Logs config ->  !!! -Dlogback.configurationFile=/opt/frontcache/conf/fc-logback.xml
			
			String debugCommentsStr = FCConfig.getProperty("front-cache.debug-comments", "false");
			if ("true".equalsIgnoreCase(debugCommentsStr))
				debugComments = true;		
			
			instance = new FrontCacheEngine();
			
//			// load hystrix fallbacks
//			Thread t = new Thread(new Runnable() {
//				public void run() {
//					// need to wait some time
//					// in filter mode servlet should be initialized first (to load fallbacks from URLs)
//					try {
//						Thread.sleep(5000); 
//					} catch (InterruptedException e) {
//						e.printStackTrace();
//					}
//					FallbackResolverFactory.getInstance(instance.httpClient);
//				}
//			});
//			t.start();				
			
		}
		return instance;
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
	
	HttpClient getHttpClient()
	{
		return httpClient;
	}
	
	private static final String DEFAULT_ORIGIN = "default";
	
	private void loadOrigins()
	{
		
		String defaultOriginHost = FCConfig.getProperty("front-cache.origin-host", "localhost");
		String defaultOriginHttpPort = FCConfig.getProperty("front-cache.origin-http-port", "80");
		String defaultOriginHttpsPort = FCConfig.getProperty("front-cache.origin-https-port", "443");
		
		domainOriginMap.put(DEFAULT_ORIGIN, 
				new Origin(defaultOriginHost, defaultOriginHttpPort, defaultOriginHttpsPort ));
		
		String domainList = FCConfig.getProperty("front-cache.domains");
		if (null != domainList)
		{
			for(String domain : domainList.split(","))
			{
				String originHost = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".origin-host", defaultOriginHost);
				String originHttpPort = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".origin-http-port", defaultOriginHttpPort);
				String originHttpsPort = FCConfig.getProperty("front-cache.domain." + domain.replace('.', '_') + ".origin-https-port", defaultOriginHttpsPort);
				
				domainOriginMap.put(domain, 
						new Origin(originHost, originHttpPort, originHttpsPort));
			}
		}
		logger.info("Loaded following origin configurations: ");

		for (String domain : domainOriginMap.keySet())
			logger.info(" -- " + domainOriginMap.get(domain));
			
		return;
	}
	
	/**
	 * 
	 * @param requestDomainName
	 * @return
	 */
	private Origin getOrigin(String requestDomainName)
	{
		for (String domainSuffix : domainOriginMap.keySet())
		{
			if (requestDomainName.endsWith(domainSuffix))
			{
				if (requestDomainName.equals(domainSuffix))
					return domainOriginMap.get(domainSuffix);
				//case for abc.com & bc.com
				if (requestDomainName.endsWith("." + domainSuffix))
					return domainOriginMap.get(domainSuffix);
			}
		}
		
		// default
		return domainOriginMap.get(DEFAULT_ORIGIN);
	}
	
	private void initialize() {
		
		logger = LoggerFactory.getLogger(FrontCacheEngine.class);
		
		loadOrigins();
		
		frontcacheHttpPort = FCConfig.getProperty("front-cache.http-port", "80");
		frontcacheHttpsPort = FCConfig.getProperty("front-cache.https-port", "443");
		
		fcHostId = FCConfig.getProperty(FCConfig.FRONTCACHE_ID_KEY);
		if (null == fcHostId)
			fcHostId = DEFAULT_FRONTCACHE_HOST_NAME_VALUE;
			
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
		
		loadCacheIgnoreURIPatterns();
		return;
	}

	private URL getOriginUrl(RequestContext context) {
		boolean isSecure = context.getRequest().isSecure();
		StringBuffer str = new StringBuffer();
		if (isSecure)
		{
			str.append("https");	
		} else {
			str.append("http");	
		}
		
		Origin origin = getOrigin(context.getRequest().getServerName());
		
		str.append("://").append(origin.getHost()).append(":");
		if (isSecure) {
			str.append(origin.getHttpsPort());
		} else {
			str.append(origin.getHttpPort());
		}

		try {
			URL routeUrl = new URL(str.toString());
			return  routeUrl;
		} catch (MalformedURLException e) {
			throw new RuntimeException("Invalid front-cache.app-origin-base-url (" + origin.getHost() + ")", e);
		}

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
				.setConnectTimeout(3000)
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
		String queryString = FCUtils.getQueryString(servletRequest, context);
		context.setRequestQueryString(queryString);
		context.setFrontCacheHost(FCUtils.getHost(servletRequest));
		context.setFrontCacheHttpPort(frontcacheHttpPort);
		context.setFrontCacheHttpsPort(frontcacheHttpsPort);
		
		context.setFrontCacheProtocol(FCUtils.getProtocol(servletRequest));
        context.setOriginURL(getOriginUrl(context));
        
        String requestId = servletRequest.getHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID);
        String requestType = FCHeaders.X_FRONTCACHE_COMPONENT_INCLUDE;
        if (null == requestId)
        {
        	requestId = UUID.randomUUID().toString();
        	requestType = FCHeaders.X_FRONTCACHE_COMPONENT_TOPLEVEL;
        } else {
            context.setRequestFromFrontcache();
        }
        
        context.setRequestId(requestId);
        context.setRequestType(requestType);

        if (null != filterChain)
        {
            context.setFilterChain(filterChain);
    		Origin origin = getOrigin(context.getRequest().getServerName());
    		context.setFrontCacheHost(origin.getHost()); // in case of filter fc host = origin host (don't put localhost it can make issues with HTTPS and certificates for includes)
        }
		return context;
    }

    
    private boolean ignoreCache(String uri)
    {
    	for (Pattern p : uriIgnorePatterns)
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
     * 
     * @throws Exception
     */
	public void processRequestInternal(RequestContext context) throws Exception
	{
		HttpServletRequest httpRequest = context.getRequest();
		String originRequestURL = getOriginUrl(context) + context.getRequestURI() + context.getRequestQueryString();
		logger.debug("originRequestURL: " + originRequestURL);
		String currentRequestBaseURL = context.getFrontCacheProtocol() + "://" + context.getFrontCacheHost() + ":" + httpRequest.getServerPort();
		logger.debug("currentRequestBaseURL: " + currentRequestBaseURL);
		
		if (context.isCacheableRequest() && !ignoreCache(context.getRequestURI())) // GET method without jsessionid
		{
			Map<String, List<String>> requestHeaders = FCUtils.buildRequestHeaders(httpRequest);

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
						WebResponse incWebResponse = includeProcessor.processIncludes(webResponse, currentRequestBaseURL, requestHeaders, httpClient, context);
						
						// copy content only (cache setting use this (parent), headers are merged inside IncludeProcessor )
						webResponse.setContent(incWebResponse.getContent());
					}
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
			List hValues = new ArrayList<String>();
			hValues.add(fcLocation);
			originResponseHeaders.put("Location", hValues);
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
	
	private void loadCacheIgnoreURIPatterns() {
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FCConfig.getConfigInputStream(CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
			if (null == is)
			{
				logger.info("Cache ignore URI patterns are not loaded from " + CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
				return;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String patternStr;
			int patternCounter = 0;
			while ((patternStr = confReader.readLine()) != null) {
				try {
					if (patternStr.trim().startsWith("#")) // handle comments
						continue;
					
					if (0 == patternStr.trim().length()) // skip empty
						continue;
					
					uriIgnorePatterns.add(Pattern.compile(patternStr));
					patternCounter++;
				} catch (PatternSyntaxException ex) {
					logger.info("Cache ignore URI pattern - " + patternStr + " is not loaded");					
				}
			}
			logger.info("Successfully loaded " + patternCounter +  " cache ignore URI patterns");					
			
		} catch (Exception e) {
			logger.info("Cache ignore URI patterns are not loaded from " + CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
		} finally {
			if (null != confReader)
			{
				try {
					confReader.close();
				} catch (IOException e) { }
			}
			if (null != is)
			{
				try {
					is.close();
				} catch (IOException e) { }
			}
		}
		
	}
		
}
