package org.frontcache.core;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.http.client.methods.CloseableHttpResponse;


/**
 * The Request Context holds request, response,  state information and data for ZuulFilters to access and share.
 * The RequestContext lives for the duration of the request and is ThreadLocal.
 * extensions of RequestContext can be substituted by setting the contextClass.
 * Most methods here are convenience wrapper methods; the RequestContext is an extension of a ConcurrentHashMap
 *
 */
@SuppressWarnings("serial")
public class RequestContext extends ConcurrentHashMap<String, Object> {

//    private static final Logger LOG = LoggerFactory.getLogger(RequestContext.class);

    protected static Class<? extends RequestContext> contextClass = RequestContext.class;


    protected static final ThreadLocal<? extends RequestContext> threadLocal = new ThreadLocal<RequestContext>() {
        @Override
        protected RequestContext initialValue() {
            try {
                return contextClass.newInstance();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    };


    public RequestContext() {
        super();
    }

    /**
     * Override the default RequestContext
     *
     * @param clazz
     */
    public static void setContextClass(Class<? extends RequestContext> clazz) {
        contextClass = clazz;
    }


    /**
     * Get the current RequestContext
     *
     * @return the current RequestContext
     */
    public static RequestContext getCurrentContext() {
        RequestContext context = threadLocal.get();
        return context;
    }

    /**
     * Convenience method to return a boolean value for a given key
     *
     * @param key
     * @return true or false depending what was set. default is false
     */
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    /**
     * Convenience method to return a boolean value for a given key
     *
     * @param key
     * @param defaultResponse
     * @return true or false depending what was set. default defaultResponse
     */
    public boolean getBoolean(String key, boolean defaultResponse) {
        Boolean b = (Boolean) get(key);
        if (b != null) {
            return b.booleanValue();
        }
        return defaultResponse;
    }

    /**
     * sets a key value to Boolen.TRUE
     *
     * @param key
     */
    public void set(String key) {
        put(key, Boolean.TRUE);
    }

    /**
     * puts the key, value into the map. a null value will remove the key from the map
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        if (value != null) put(key, value);
        else remove(key);
    }

    /**
     * @return the HttpServletRequest from the "request" key
     */
    public HttpServletRequest getRequest() {
        return (HttpServletRequest) get("request");
    }

    /**
     * sets the HttpServletRequest into the "request" key
     *
     * @param request
     */
    public void setRequest(HttpServletRequest request) {
        put("request", request);
    }

    /**
     * @return the HttpServletResponse from the "response" key
     */
    public HttpServletResponse getResponse() {
        return (HttpServletResponse) get("response");
    }

    /**
     * sets the "response" key to the HttpServletResponse passed in
     *
     * @param response
     */
    public void setResponse(HttpServletResponse response) {
        set("response", response);
    }

    /**
     * returns a set throwable
     *
     * @return a set throwable
     */
    public Throwable getThrowable() {
        return (Throwable) get("throwable");

    }

    /**
     * sets a throwable
     *
     * @param th
     */
    public void setThrowable(Throwable th) {
        put("throwable", th);

    }

    /**
     * sets frontCacheHost
     *
     * @param frontCacheHost a URL
     */
    public void setFrontCacheHost(String frontCacheHost) {
        set("frontCacheHost", frontCacheHost);
    }

    /**
     * @return "frontCacheHost" URL
     */
    public String getFrontCacheHost() {
        return (String) get("frontCacheHost");
    }

    /**
     * 
     * @param frontCacheProtocol
     */
    public void setFrontCacheProtocol(String frontCacheProtocol) {
        set("frontCacheProtocol", frontCacheProtocol);
    }

    /**
     * 
     * @return
     */
    public String getFrontCacheProtocol() {
        return (String) get("frontCacheProtocol");
    }
    
    /**
     * sets originHost
     *
     * @param originHost a URL
     */
    public void setOriginHost(URL originHost) {
        set("originHost", originHost);
    }

    /**
     * @return "originHost" URL
     */
    public URL getOriginHost() {
        return (URL) get("originHost");
    }

    
    /**
     * sets the "responseBody" value as a String. This is the response sent back to the client.
     *
     * @param body
     */
    public void setResponseBody(String body) {
        set("responseBody", body);
    }

    /**
     * @return the String response body to be snt back to the requesting client
     */
    public String getResponseBody() {
        return (String) get("responseBody");
    }

    /**
     * sets the InputStream of the response into the responseDataStream
     *
     * @param responseDataStream
     */
    public void setResponseDataStream(InputStream responseDataStream) {
        set("responseDataStream", responseDataStream);
    }

    /**
     * sets the flag responseGZipped if the response is gzipped
     *
     * @param gzipped
     */
    public void setResponseGZipped(boolean gzipped) {
        put("responseGZipped", gzipped);
    }

    /**
     * @return true if responseGZipped is true (the response is gzipped)
     */
    public boolean getResponseGZipped() {
        return getBoolean("responseGZipped", true);
    }

    
    /**
     * @return the InputStream Response
     */
    public InputStream getResponseDataStream() {
        return (InputStream) get("responseDataStream");
    }

    /**
     * returns the response status code. Default is 200
     *
     * @return
     */
    public int getResponseStatusCode() {
        return get("responseStatusCode") != null ? (Integer) get("responseStatusCode") : 500;
    }


    /**
     * Use this instead of response.setStatusCode()
     *
     * @param nStatusCode
     */
    public void setResponseStatusCode(int nStatusCode) {
        getResponse().setStatus(nStatusCode);
        set("responseStatusCode", nStatusCode);
    }

    /**
     * the Origin response headers
     *
     * @return the List<Pair<String, String>> of headers sent back from the origin
     */
    public MultiValuedMap<String, String> getOriginResponseHeaders() {
        if (get("originResponseHeaders") == null) {
        	MultiValuedMap<String, String> originResponseHeaders = new ArrayListValuedHashMap<>();
            putIfAbsent("originResponseHeaders", originResponseHeaders);
        }
        return (MultiValuedMap<String, String>) get("originResponseHeaders");
    }
    
    /**
     * check if response has "Content-Type" header with "text" inside
     * @return
     */
    public boolean isCacheableResponse()
    {
    	MultiValuedMap<String, String> originResponseHeaders = getOriginResponseHeaders();
		
		for (String key : originResponseHeaders.keySet()) {
			for (String value : originResponseHeaders.get(key)) {
				if ("Content-Type".equals(key) 
						&& -1 < value.indexOf("text"))
					return true;
			}
		}
    	return false;
    }

    /**
     * 
     * @return
     */
    public boolean isCacheableRequest()
    {
    	
    	if (!"GET".equals(FCUtils.getVerb(this.getRequest())))
    		return false;
    	
        final String requestEncoding = this.getRequest().getHeader(FCHeaders.ACCEPT);
        return requestEncoding != null && requestEncoding.toLowerCase().contains("text");
    }
    
    /**
     * adds a header to the origin response headers
     *
     * @param name
     * @param value
     */
    public void addOriginResponseHeader(String name, String value) {
        getOriginResponseHeaders().put(name, value);
    }

    /**
     * returns the content-length of the origin response
     *
     * @return the content-length of the origin response
     */
    public Long getOriginContentLength() {
        return (Long) get("originContentLength");
    }

    /**
     * sets the content-length from the origin response
     *
     * @param v
     */
    public void setOriginContentLength(Long v) {
        set("originContentLength", v);
    }
    
    public void setRequestURI(String uri)
    {
        set("requestURI", uri);
    }
    
    public String getRequestURI()
    {
    	return (String) get("requestURI");
    }

    public void setRequestQueryString(String requestQueryString)
    {
        set("requestQueryString", requestQueryString);
    }
    
    public String getRequestQueryString()
    {
    	return (String) get("requestQueryString");
    }
    
    /**
     * sets the content-length from the origin response
     *
     * @param v parses the string into an int
     */
    public void setOriginContentLength(String v) {
        try {
            final Long i = Long.valueOf(v);
            set("originContentLength", i);
        } catch (NumberFormatException e) {
        	e.printStackTrace();
//            LOG.warn("error parsing origin content length", e);
        }
    }

    /**
     * @return true if the request body is chunked
     */
    public boolean isChunkedRequestBody() {
        final Object v = get("chunkedRequestBody");
        return (v != null) ? (Boolean) v : false;
    }

    /**
     * sets chunkedRequestBody to true
     */
    public void setChunkedRequestBody() {
        this.set("chunkedRequestBody", Boolean.TRUE);
    }

    /**
     * @return true is the client request can accept gzip encoding. Checks the "accept-encoding" header
     */
    public boolean isGzipRequested() {
        final String requestEncoding = this.getRequest().getHeader(FCHeaders.ACCEPT_ENCODING);
        return requestEncoding != null && requestEncoding.toLowerCase().contains("gzip");
    }

    /**
     * unsets the threadLocal context. Done at the end of the request.
     */
    public void unset() {
        threadLocal.remove();
    }


    /**
     * @return Map<String, List<String>>  of the request Query Parameters
     */
    public Map<String, List<String>> getRequestQueryParams() {
        return (Map<String, List<String>>) get("requestQueryParams");
    }

    /**
     * sets the request query params list
     *
     * @param qp Map<String, List<String>> qp
     */
    public void setRequestQueryParams(Map<String, List<String>> qp) {
        put("requestQueryParams", qp);
    }

    public CloseableHttpResponse getHttpClientResponse() {
        return (CloseableHttpResponse) get("httpClientResponse");
    }
    
    public void setHttpClientResponse(CloseableHttpResponse response) {
    	this.set("httpClientResponse", response);
    }


}
