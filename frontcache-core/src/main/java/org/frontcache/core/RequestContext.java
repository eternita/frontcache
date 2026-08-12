/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The Request Context holds request, response,  state information and data to access and share.
 * The RequestContext lives for the duration of the request and is ThreadLocal.
 * extensions of RequestContext can be substituted by setting the contextClass.
 * Most methods here are convenience wrapper methods; the RequestContext is an extension of a ConcurrentHashMap
 *
 */
@SuppressWarnings("serial")
public class RequestContext extends ConcurrentHashMap<String, Object> {

    private static final Logger logger = LoggerFactory.getLogger(RequestContext.class);

    private static final String FRONTCACHE_REQUEST_TYPE = "frontcacheRequestType"; // { toplevel | include }
    private static final String FRONTCACHE_REQUEST_ID = "frontcacheRequestID"; //

    public RequestContext() {
        super();
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

    public void setOriginURL(URL originURL) {
        set("originURL", originURL);
    }

    public URL getOriginURL() {
        return (URL) get("originURL");
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
    @SuppressWarnings("unchecked")
	public Map<String, List<String>> getOriginResponseHeaders() {
        if (get("originResponseHeaders") == null) {
        	Map<String, List<String>> originResponseHeaders = new HashMap<String, List<String>>();
            putIfAbsent("originResponseHeaders", originResponseHeaders);
        }
        return (Map<String, List<String>>) get("originResponseHeaders");
    }

    /**
     * check if response has "Content-Type" header with "text" inside
     * @return
     */
    public boolean isCacheableResponse()
    {
    	Map<String, List<String>> originResponseHeaders = getOriginResponseHeaders();

		for (String key : originResponseHeaders.keySet()) {
			for (String value : originResponseHeaders.get(key)) {
				if (FCHeaders.CONTENT_TYPE.equals(key)
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

    	if ("GET".equals(this.getRequest().getMethod().toUpperCase()))
    		return true;

    	if ("HEAD".equals(this.getRequest().getMethod().toUpperCase()))
    		return true;

//    	if (-1 < this.getRequestURI().indexOf("jsessionid="))
//    		return false;

    	return false;
    	//bot's send requests without header (accept=text/html) #36
//        final String requestEncoding = this.getRequest().getHeader(FCHeaders.ACCEPT);
//        return requestEncoding != null && requestEncoding.toLowerCase().contains("text");
    }

    /**
     * adds a header to the origin response headers
     *
     * @param name
     * @param value
     */
    public void addOriginResponseHeader(String name, String value) {

    	Map<String, List<String>> originResponseHeaders = getOriginResponseHeaders();

		List<String> hValues = originResponseHeaders.get(name);
		if(null == hValues)
		{
			hValues = new ArrayList<String>();
			originResponseHeaders.put(name, hValues);
		}
		hValues.add(value);
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
     * @return Map<String, List<String>>  of the request Query Parameters
     */
    @SuppressWarnings("unchecked")
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

    /**
     * How many of the two parties (the Hystrix worker thread that opens the origin response, and
     * the request thread that streams its body to the client) are done with it.
     * Not carried over by copy() - a copied context is a fresh exchange.
     */
    private final transient AtomicInteger httpClientResponseParties = new AtomicInteger(0);

    /**
     * Releases the origin response opened by FC_BypassCache, closing it once BOTH parties are done.
     *
     * The response is opened on a Hystrix worker thread but its body is streamed by the request
     * thread, so neither thread can close it unilaterally. On a Hystrix TIMEOUT the request thread
     * finishes early (through the fallback) while the worker is still inside httpclient.execute() -
     * a blocking socket read ignores the interrupt - and then stashes a response nobody is left to
     * close, leaking the pooled connection permanently (leased entries are never reclaimed by
     * closeExpiredConnections). Both threads call this in a finally; whichever arrives last does the
     * close, so the connection is returned exactly once no matter which one wins the race.
     */
    public void releaseHttpClientResponse() {

    	if (2 > httpClientResponseParties.incrementAndGet())
    		return; // the other party is still using the response (or has yet to open it)

    	CloseableHttpResponse response = getHttpClientResponse();
    	if (null == response)
    		return; // no origin response was ever opened (filter mode, or the call failed)

    	try {
    		response.close();
    	} catch (IOException e) {
    		logger.error("Can't close origin response for " + get("currentRequestURL"), e);
    	}
    }

    public void setFrontCacheHttpPort(String frontCacheHttpPort) {
        set("frontCacheHttpPort", frontCacheHttpPort);
    }

    public String getFrontCacheHttpPort() {
        return (String) get("frontCacheHttpPort");
    }

    public void setFrontCacheHttpsPort(String frontCacheHttpsPort) {
        set("frontCacheHttpsPort", frontCacheHttpsPort);
    }

    public String getFrontCacheHttpsPort() {
        return (String) get("frontCacheHttpsPort");
    }

    public void setFilterChain(FilterChain filterChain) {
        set("filterChain", filterChain);
    }

    public FilterChain getFilterChain() {
        return (FilterChain) get("filterChain");
    }

    /**
     * Check if run as ServletFilter
     *
     * @return
     */
    public boolean isFilterMode()
    {
    	if (null != getFilterChain())
    		return true;

    	return false;
    }

    /**
     *
     * @param frontCacheId
     */
    public void setFrontCacheId(String frontCacheId) {
        set("frontCacheId", frontCacheId);
    }

    /**
     *
     * @return
     */
    public String getFrontCacheId() {
        return (String) get("frontCacheId");
    }

    public void setHystrixFallback() {
        set("hystrixError", true);
    }

    public void setHystrixFallback(String reason) {
        set("hystrixError", true);
        if (null != reason)
            set("hystrixErrorReason", reason);
    }

    public boolean isHystrixFallback() {
        return getBoolean("hystrixError", false);
    }

    /**
     * @return the hystrix failure category (short-circuited|timeout|rejected|bad-request|failure)
     *         when known, otherwise "error". Only meaningful when {@link #isHystrixFallback()} is true.
     */
    public String getHystrixReason() {
        Object reason = get("hystrixErrorReason");
        return (null != reason) ? reason.toString() : "error";
    }

    public void setRequestType(String frontcacheRequestType) {
        set(FRONTCACHE_REQUEST_TYPE, frontcacheRequestType);
    }

    public String getRequestType() {
        return (String) get(FRONTCACHE_REQUEST_TYPE);
    }

    public void setRequestId(String frontcacheRequestId) {
        set(FRONTCACHE_REQUEST_ID, frontcacheRequestId);
    }

    public String getRequestId() {
        return (String) get(FRONTCACHE_REQUEST_ID);
    }

    public void setRequestFromFrontcache() {
        set("RequestFromFrontcache", true);
    }

    public boolean getRequestFromFrontcache() {
        return getBoolean("RequestFromFrontcache", false);
    }


	public String getCurrentRequestURL() {
		return (String) get("currentRequestURL");
	}


	public void setCurrentRequestURL(String currentRequestURL) {
        set("currentRequestURL", currentRequestURL);
	}

	// bot | browser
	public String getClientType() {
		return (String) get("currentClientType");
	}

	// bot | browser
	public void setClientType(String currentClientType) {
        set("currentClientType", currentClientType);
	}

	public RequestContext copy() {

		RequestContext copy = new RequestContext();

		copy.putAll(this);
		copy.setRequestId(UUID.randomUUID().toString());

		return copy;
	}

    public void setDomainContext(DomainContext domainContext) {
        set("domainContext", domainContext);
    }

    public DomainContext getDomainContext() {
        return (DomainContext) get("domainContext");
    }

    public void setLogToHTTPHeaders() {
        set("LogToHTTPHeaders", true);
    }

    public boolean getLogToHTTPHeaders() {
        return getBoolean("LogToHTTPHeaders", false);
    }

    public void setIncludeLevel(String includeLevel) {
        set("IncludeLevel", includeLevel);
    }

    public String getIncludeLevel() {
        return get("IncludeLevel") != null ? (String) get("IncludeLevel") : "0";
    }

    public void setToplevelCached() {
        set("ToplevelCached", true);
    }

    public boolean isToplevelCached() {
        return getBoolean("ToplevelCached", false);
    }


	@Override
	public String toString() {
		String str = super.toString();
		str = str.replaceAll("(\\r|\\n|\\r\\n)+", " ");
		return "RequestContext: " + str;
	}


}
