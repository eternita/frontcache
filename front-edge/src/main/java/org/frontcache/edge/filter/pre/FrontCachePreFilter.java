package org.frontcache.edge.filter.pre;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.frontcache.edge.FCConstants;
import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;

public class FrontCachePreFilter extends ZuulFilter{

	
	private String appOriginBaseURLStr = null;
	private String forwardHttpPort = null;
	private String forwardHttpsPort = null;
	
	List<Pattern> patterns = new LinkedList<Pattern>();
	private FrontCacheEngine fcEngine;

	public FrontCachePreFilter() {
		appOriginBaseURLStr = FCConfig.getProperty("front-cache.app-origin-base-url");
		forwardHttpPort = FCConfig.getProperty("front-cache.forward-http-port");
		forwardHttpsPort = FCConfig.getProperty("front-cache.forward-https-port");
		fcEngine = FrontCacheEngine.getFrontCache();
		Config cacheConfig = ConfigFactory.load().getConfig(FCConstants.CACHE_REGEX);
		for (Map.Entry<String, ConfigValue> entry : cacheConfig.entrySet()) {
			try {
				Pattern pattern = Pattern.compile(entry.getValue().render().replaceAll("\"", ""));
				patterns.add(pattern);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		};
	}
 

    @Override
    public String filterType() {
        return "pre";
    }

    @Override
    public int filterOrder() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean shouldFilter() {
        RequestContext context = RequestContext.getCurrentContext();
        HttpServletRequest httpRequest = context.getRequest();
    	for (Pattern pattern: patterns)
    	{
    		Matcher matcher = pattern.matcher(httpRequest.getRequestURL().toString());
    		if(matcher.find()){
    			return true;
    		}
    	}
    	context.setRouteHost(getRouteUrl(context));
    	return false;
    }

    @Override
    public Object run() {
        RequestContext context = RequestContext.getCurrentContext();
        HttpServletRequest httpRequest = context.getRequest();
        HttpServletResponse httpResponse = context.getResponse();
        
        try {
        	fcEngine.init(httpRequest, httpResponse);
        	
            fcEngine.processRequest();
            
        } catch (Throwable e) {
        	e.printStackTrace();
        	// TODO: handle error
        } finally {
            RequestContext.getCurrentContext().unset();
          
        }
        

        return null;
    }
    
//	private String getQueryString() throws UnsupportedEncodingException {
//		HttpServletRequest request = RequestContext.getCurrentContext().getRequest();
//		MultiValueMap<String, String> params=helper.buildZuulRequestQueryParams(request);
//		StringBuilder query=new StringBuilder();
//		for (Map.Entry<String, List<String>> entry : params.entrySet()) {
//			String key=URLEncoder.encode(entry.getKey(), "UTF-8");
//			for (String value : entry.getValue()) {
//				query.append("&");
//				query.append(key);
//				query.append("=");
//				query.append(URLEncoder.encode(value, "UTF-8"));
//			}
//		}
//		return (query.length()>0) ? "?" + query.substring(1) : "";
//	}
    
//	private FrontCacheHttpResponseWrapper getHttpResponseWrapper(HttpServletResponse httpResponse)
//	{
//		FrontCacheHttpResponseWrapper wrappedResponse = new HttpResponseWrapperImpl(httpResponse);
//		return wrappedResponse;
//	}
    
    private  URL getRouteUrl(RequestContext context)
    {
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
        
       return getUrl(str.toString());
      
    }
    


    private URL getUrl(String target) {
        try {
            return new URL(target);
        }
        catch (MalformedURLException ex) {
            throw new IllegalStateException("Target URL is malformed", ex);
        }
    }
}
