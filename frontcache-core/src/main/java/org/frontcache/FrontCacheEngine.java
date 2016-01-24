package org.frontcache;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorManager;
import org.frontcache.reqlog.RequestLogger;

public class FrontCacheEngine {

	private String appOriginBaseURLStr = null;
	private String forwardHttpPort = null;
	private String forwardHttpsPort = null;
	private String keyStorePath = null;
	private String keyStorePassword = null;
	
	private String fcHostId = null;	// used to determine which front cache processed request (forwarded by GEO Load Balancer e.g. route53 AWS)
	
	public final static String DEFAULT_FRONTCACHE_HOST_NAME_VALUE = "undefined-front-cache-host";
	
	private static int fcConnectionsMaxTotal = 200;
	
	private static int fcConnectionsMaxPerRoute = 20;

	private IncludeProcessor includeProcessor = null;
	
	private CacheProcessor cacheProcessor = null; 

	protected Logger logger = Logger.getLogger(getClass().getName());
	
	private final Timer connectionManagerTimer = new Timer("FrontCacheEngine.connectionManagerTimer", true);

	private PoolingHttpClientConnectionManager connectionManager;
	
	private CloseableHttpClient httpClient;
	
	public FrontCacheEngine() {
		initialize();
	}
	
	private void initialize() {

		keyStorePath = FCConfig.getProperty("front-cache.keystore-path");
		keyStorePassword = FCConfig.getProperty("front-cache.keystore-password");
		
		appOriginBaseURLStr = FCConfig.getProperty("front-cache.app-origin-base-url");
		forwardHttpPort = FCConfig.getProperty("front-cache.forward-http-port");
		forwardHttpsPort = FCConfig.getProperty("front-cache.forward-https-port");

		
		fcHostId = FCConfig.getProperty("front-cache.host-name");
		if (null == fcHostId)
			fcHostId = DEFAULT_FRONTCACHE_HOST_NAME_VALUE;
			
		cacheProcessor = CacheManager.getInstance();

		includeProcessor = IncludeProcessorManager.getInstance();

		includeProcessor.setCacheProcessor(cacheProcessor);

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
	}

	private URL getRouteUrl(RequestContext context) {
		boolean isSecure = context.getRequest().isSecure();
		StringBuffer str = new StringBuffer();
		if (isSecure)
		{
			str.append("https");	
		} else {
			str.append("http");	
		}
		str.append("://").append(appOriginBaseURLStr).append(":");
		if (isSecure) {
			str.append(forwardHttpsPort);
		} else {
			str.append(forwardHttpPort);
		}

		try {
			URL routeUrl = new URL(str.toString());
			return  routeUrl;
		} catch (MalformedURLException e) {
			throw new RuntimeException("Invalid front-cache.app-origin-base-url (" + appOriginBaseURLStr + ")", e);
		}

	}
	
	public void stop() {
		connectionManagerTimer.cancel();
	}

	
	private CloseableHttpClient newClient() {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setSocketTimeout(10000)
				.setConnectTimeout(2000)
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
		        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
		        trustStore.load(new FileInputStream(keyStorePath), keyStorePassword.toCharArray());

		        MySSLSocketFactory sf = new MySSLSocketFactory(trustStore);
		        sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
				

			final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
					.register("http", PlainConnectionSocketFactory.INSTANCE)
					.register("https", sf)
					.build();

			connectionManager = new PoolingHttpClientConnectionManager(registry);
			
			connectionManager.setMaxTotal(fcConnectionsMaxTotal);
			connectionManager.setDefaultMaxPerRoute(fcConnectionsMaxPerRoute);
			
			return connectionManager;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
	
	public static class MySSLSocketFactory extends SSLSocketFactory {
		private SSLContext sslContext = SSLContext.getInstance("TLS");

		public MySSLSocketFactory(KeyStore truststore) throws NoSuchAlgorithmException,
				KeyManagementException, KeyStoreException, UnrecoverableKeyException {
			super(truststore);
			TrustManager tm = new X509TrustManager() {

				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType)
						throws CertificateException {
				}

				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType)
						throws CertificateException {
				}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return null;
				}

			};
			TrustManager[] tms = new TrustManager[1];
			tms[0] = tm;
			this.sslContext.init(null, tms, null);
		}

		@Override
		public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
				throws IOException, UnknownHostException {
			return this.sslContext.getSocketFactory().createSocket(socket, host, port,
					autoClose);
		}

		@Override
		public Socket createSocket() throws IOException {
			return this.sslContext.getSocketFactory().createSocket();
		}

	}
	
    public void init(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {

        RequestContext ctx = RequestContext.getCurrentContext();
        ctx.setRequest(servletRequest);
        ctx.setResponse(servletResponse);
		String uri = FCUtils.buildRequestURI(servletRequest);
		ctx.setRequestURI(uri);
		String queryString = FCUtils.getQueryString(servletRequest);
		ctx.setRequestQueryString(queryString);
		ctx.setFrontCacheHost(FCUtils.getBaseURL(servletRequest));
        ctx.setOriginHost(getRouteUrl(ctx));
	
    }	
    
    
	public void processRequest() throws Exception
	{
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletRequest httpRequest = context.getRequest();
		String originRequestURL = getRouteUrl(context) + context.getRequestURI() + context.getRequestQueryString();

		String currentRequestBaseURL = context.getFrontCacheHost();
		
//		System.out.println("-- " + currentRequestBaseURL);
		
		if (context.isCacheableRequest()) // GET method & Accept header contain 'text'
		{
			MultiValuedMap<String, String> requestHeaders = FCUtils.buildRequestHeaders(httpRequest);

			WebResponse webResponse = null;
			try
			{
				webResponse = cacheProcessor.processRequest(originRequestURL, requestHeaders, httpClient);
			} catch (FrontCacheException fce) {
				// content/response is not cacheable (e.g. response type is not text) 
				fce.printStackTrace();
			}

			if (null != webResponse)
			{
				// include processor
				if (null != webResponse.getContent())
				{
					String content = webResponse.getContent(); 
					content = includeProcessor.processIncludes(content, currentRequestBaseURL, requestHeaders, httpClient);
					webResponse.setContent(content);
				}
				
				
				addResponseHeaders(webResponse);
				writeResponse(webResponse);
				
				if (null != context.getHttpClientResponse())
					context.getHttpClientResponse().close();
				return;
			} else {
				// content/response is not cacheable (e.g. response type is not text) 
				
				// do dynamic call below (forwardToOrigin)
			}

		}
		
		// do dynamic call to origin
		{
			long start = System.currentTimeMillis();
			boolean isRequestCacheable = false;
			boolean isRequestDynamic = true;
			long lengthBytes = -1; // TODO: set/get content length from context or just keep -1 ?
			forwardToOrigin();		
			RequestLogger.logRequest(originRequestURL, isRequestCacheable, isRequestDynamic, System.currentTimeMillis() - start, lengthBytes);			
			addResponseHeaders();
			writeResponse();
			if (null != context.getHttpClientResponse())
				context.getHttpClientResponse().close();
		}		
		return;
	}

	
	private void forwardToOrigin()
	{
		RequestContext context = RequestContext.getCurrentContext();
		

		HttpServletRequest request = context.getRequest();
		MultiValuedMap<String, String> headers = FCUtils.buildRequestHeaders(request);
		MultiValuedMap<String, String> params = FCUtils.builRequestQueryParams(request);
		String verb = FCUtils.getVerb(request);
		InputStream requestEntity = getRequestBody(request);
		String uri = context.getRequestURI();

		try {
			HttpResponse response = forward(httpClient, verb, uri, request, headers, params, requestEntity);
			
			// response 2 context
			setResponse(response);
			
		}
		catch (Exception ex) {
			ex.printStackTrace();
			context.set("error.status_code", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			context.set("error.exception", ex);
		}
		
		return;
	}


	/**
	 * forward all kind of requests (GET, POST, PUT, ...)
	 * 
	 * @param httpclient
	 * @param verb
	 * @param uri
	 * @param request
	 * @param headers
	 * @param params
	 * @param requestEntity
	 * @return
	 * @throws Exception
	 */
	private HttpResponse forward(HttpClient httpclient, String verb, String uri, HttpServletRequest request,
			MultiValuedMap<String, String> headers, MultiValuedMap<String, String> params, InputStream requestEntity)
					throws Exception {
		RequestContext context = RequestContext.getCurrentContext();

		URL host = context.getOriginHost();
		HttpHost httpHost = FCUtils.getHttpHost(host);
		uri = (host.getPath() + uri).replaceAll("/{2,}", "/");
		
//		System.out.println("forward (no-cache) " + httpHost + uri);
		
		HttpRequest httpRequest;
		switch (verb.toUpperCase()) {
		case "POST":
			HttpPost httpPost = new HttpPost(uri + context.getRequestQueryString());
			httpRequest = httpPost;
			httpPost.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		case "PUT":
			HttpPut httpPut = new HttpPut(uri + context.getRequestQueryString());
			httpRequest = httpPut;
			httpPut.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		case "PATCH":
			HttpPatch httpPatch = new HttpPatch(uri + context.getRequestQueryString());
			httpRequest = httpPatch;
			httpPatch.setEntity(new InputStreamEntity(requestEntity, request.getContentLength()));
			break;
		default:
			httpRequest = new BasicHttpRequest(verb, uri + context.getRequestQueryString());
		}
		
		
		try {
			httpRequest.setHeaders(FCUtils.convertHeaders(headers));
			Header acceptEncoding = httpRequest.getFirstHeader("accept-encoding");
			if (acceptEncoding != null && acceptEncoding.getValue().contains("gzip"))
			{
				httpRequest.setHeader("accept-encoding", "gzip");
			}
			HttpResponse originResponse = httpclient.execute(httpHost, httpRequest);
			return originResponse;
		} finally {
			// When HttpClient instance is no longer needed,
			// shut down the connection manager to ensure
			// immediate deallocation of all system resources
			// httpclient.getConnectionManager().shutdown();
		}
	}	
	
	private void setResponse(HttpResponse response) throws IOException {
		
		RequestContext context = RequestContext.getCurrentContext();
		context.setHttpClientResponse((CloseableHttpResponse) response);
		
		setResponse(response.getStatusLine().getStatusCode(),
				response.getEntity() == null ? null : response.getEntity().getContent(),
				FCUtils.revertHeaders(response.getAllHeaders()));
	}
	
	private void setResponse(int status, InputStream entity, MultiValuedMap<String, String> headers) throws IOException {
		RequestContext context = RequestContext.getCurrentContext();
		
		context.setResponseStatusCode(status);
		
		if (entity != null) {
			context.setResponseDataStream(entity);
		}
		
		for (String key : headers.keySet()) {
			for (String value : headers.get(key)) {
				context.addOriginResponseHeader(key, value);
			}
		}

	}	
	

	private InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		try {
			requestEntity = request.getInputStream();
		}
		catch (IOException ex) {
			// no requestBody is ok.
		}
		return requestEntity;
	}	
	
	private void writeResponse() throws Exception {
		RequestContext context = RequestContext.getCurrentContext();
		// there is no body to send
		if (context.getResponseBody() == null && context.getResponseDataStream() == null) {
			return;
		}
		HttpServletResponse servletResponse = context.getResponse();
//		servletResponse.setCharacterEncoding("UTF-8");
		OutputStream outStream = servletResponse.getOutputStream();
		InputStream is = null;
		try {
			if (RequestContext.getCurrentContext().getResponseBody() != null) {
				String body = RequestContext.getCurrentContext().getResponseBody();
				FCUtils.writeResponse(new ByteArrayInputStream(body.getBytes()), outStream);
				return;
			}
			boolean isGzipRequested = false;
			final String requestEncoding = context.getRequest().getHeader(
					FCHeaders.ACCEPT_ENCODING);

			if (requestEncoding != null
					&& FCUtils.isGzipped(requestEncoding)) {
				isGzipRequested = true;
			}
			is = context.getResponseDataStream();
			InputStream inputStream = is;
			if (is != null) {
/*				
				if (context.sendZuulResponse()) {
					// if origin response is gzipped, and client has not requested gzip,
					// decompress stream
					// before sending to client
					// else, stream gzip directly to client
					if (context.getResponseGZipped() && !isGzipRequested) {
						try {
							inputStream = new GZIPInputStream(is);
						}
						catch (java.util.zip.ZipException ex) {
							log.debug("gzip expected but not "
									+ "received assuming unencoded response "
									+ RequestContext.getCurrentContext().getRequest()
											.getRequestURL().toString());
							inputStream = is;
						}
					}
					else if (context.getResponseGZipped() && isGzipRequested) {
						servletResponse.setHeader(FCHeaders.CONTENT_ENCODING, "gzip");
					}
//					writeResponse(inputStream, outStream);
				}
//*/				
				
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

	private void writeResponse(WebResponse webResponse) throws Exception {
		RequestContext context = RequestContext.getCurrentContext();

		// there is no body to send
		if (null == webResponse.getContent()) {
			return;
		}
		
		HttpServletResponse servletResponse = context.getResponse();
		servletResponse.setCharacterEncoding("UTF-8");
		OutputStream outStream = servletResponse.getOutputStream();
		try {
			String body = webResponse.getContent();
			FCUtils.writeResponse(new ByteArrayInputStream(body.getBytes("UTF-8")), outStream);
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
	
	private void addResponseHeaders() {
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletResponse servletResponse = context.getResponse();
		MultiValuedMap<String, String> originResponseHeaders = context.getOriginResponseHeaders();

		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_HOST, fcHostId);
		servletResponse.setStatus(context.getResponseStatusCode());
		
		if (originResponseHeaders != null) {
			for (String key : originResponseHeaders.keySet()) {
				for (String value : originResponseHeaders.get(key)) {
					servletResponse.addHeader(key, value);
				}
			}
		}
		RequestContext ctx = RequestContext.getCurrentContext();
		Long contentLength = ctx.getOriginContentLength();
		// Only inserts Content-Length if origin provides it and origin response is not
		// gzipped
//		if (SET_CONTENT_LENGTH.get()) {
			if (contentLength != null && !ctx.getResponseGZipped()) {
				servletResponse.setContentLength(contentLength.intValue());
			}
//		}
	}	

	private void addResponseHeaders(WebResponse webResponse) {
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletResponse servletResponse = context.getResponse();

		servletResponse.setStatus(webResponse.getStatusCode());
		
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_HOST, fcHostId);
		
		if (webResponse.getHeaders() != null) {
			for (String name : webResponse.getHeaders().keySet()) {
				for (String value : webResponse.getHeaders().get(name)) {
					servletResponse.addHeader(name, value);
				}
			}
		}
		// TO
		
//		RequestContext ctx = RequestContext.getCurrentContext();
//		Long contentLength = ctx.getOriginContentLength();
//		// Only inserts Content-Length if origin provides it and origin response is not
//		// gzipped
////		if (SET_CONTENT_LENGTH.get()) {
//			if (contentLength != null && !ctx.getResponseGZipped()) {
//				servletResponse.setContentLength(contentLength.intValue());
//			}
////		}
	}	
	
		
}
