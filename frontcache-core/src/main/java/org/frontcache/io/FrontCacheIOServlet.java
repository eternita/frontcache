package org.frontcache.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.frontcache.cache.CacheManager;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackConfigEntry;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FrontCacheIOServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	protected Logger logger = LoggerFactory.getLogger(getClass());
	private ObjectMapper jsonMapper = new ObjectMapper();
	
	FrontCacheEngine fcEngine = null;

	private int managementPort = -1; // management port for security

	
	public FrontCacheIOServlet() {
	}
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		fcEngine = FrontCacheEngine.getFrontCache();
		
		String managementPortStr = FCConfig.getProperty("front-cache.management.port");
		if (null == managementPortStr || managementPortStr.trim().length() == 0)
			logger.warn("Frontcache Hystrix Stream is not restricted to specific port. Hystrix Stream is accessible for all connectors");
		else {
			try
			{
				managementPort = Integer.parseInt(managementPortStr);
			} catch (Exception ex) {
				ex.printStackTrace();
				logger.error("Can't read managementPort=" + managementPortStr + ". Frontcache Hystrix Stream is not restricted to specific port. Hystrix Stream is accessible for all connectors");
			}
		}
		
		return;
	}

	@Override
	public void destroy() {
		super.destroy();
		FrontCacheEngine.destroy();
		fcEngine = null;
	}	
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException 
	{
		process(req, resp);
		return;
	}


	private void process(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{
		
		if (-1 < managementPort && managementPort != request.getServerPort())
		{
			response.getOutputStream().write(jsonMapper.writeValueAsBytes(new AccessDeniedActionResponse(managementPort)));
			return;
		}
		
		String action = request.getParameter("action");
		if (null == action)
			action = "";
		
		ActionResponse aResponse = null;
		switch (action)
		{
		case FrontcacheAction.INVALIDATE:
			aResponse = invalidate(request);
			break;
			
		case FrontcacheAction.GET_CACHE_STATE:
			aResponse = getCacheStatus(request);
			break;
			
		case FrontcacheAction.GET_CACHED_KEYS:
			getCachedKeys(request, response);
			return;
			
		case FrontcacheAction.GET_FROM_CACHE:
			aResponse = getFromCache(request);
			break;
			
		case FrontcacheAction.DUMP_KEYS:
			aResponse = startDumpKeys(request);
			break;
			
		case "reload":
			aResponse = reload(request);
			break;

		case FrontcacheAction.RELOAD_FALLBACKS:
			aResponse = reloadFallbacks(request);
			break;
			
		case FrontcacheAction.GET_FALLBACK_CONFIGS:
			aResponse = getFallbackConfigs(request);
			break;
			
		case FrontcacheAction.GET_BOTS:
			aResponse = getBots(request);
			break;
			
		case FrontcacheAction.GET_DYNAMIC_URLS:
			aResponse = getDynamicURLs(request);
			break;
			
			default:
				aResponse = new HelpActionResponse(FrontcacheAction.actionsDescriptionMap);
			
		}
		response.getOutputStream().write(jsonMapper.writeValueAsBytes(aResponse));
	}

	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse invalidate(HttpServletRequest req)
	{
		String filter = req.getParameter("filter");
		ActionResponse aResponse = new InvalidateActionResponse(filter);
		if (null == filter)
		{
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			return aResponse;
		}
			
		if ("*".equals(filter))
			CacheManager.getInstance().removeFromCacheAll();
		else
			CacheManager.getInstance().removeFromCache(filter);
			
		logger.info("Invalidation for filter: " + filter);
		return aResponse;
	}

	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse getCacheStatus(HttpServletRequest req)
	{
		Map<String, String> state = CacheManager.getInstance().getCacheStatus();
		ActionResponse aResponse = new CacheStatusActionResponse(state);
			
		return aResponse;
	}
	
	/**
	 * 
	 * @param req
	 * @return
	 */
	private void getCachedKeys(HttpServletRequest req, HttpServletResponse resp)
	{
		resp.setContentType("text");
		OutputStream os = null;
		try {
			os = resp.getOutputStream();
			for (String url : CacheManager.getInstance().getCachedKeys())
			{
				os.write((url + "\n").getBytes());
			}
			os.flush();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (null != os)
					os.close(); 
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return;
	}
	
	/**
	 * @param req
	 * @return
	 */
	private ActionResponse getFromCache(HttpServletRequest req)
	{
		String key = req.getParameter("key");
		if (null == key)
			return new GetFromCacheActionResponse(key);
		
		WebResponse webResponse = CacheManager.getInstance().getFromCache(key);
		GetFromCacheActionResponse actionResponse = new GetFromCacheActionResponse(key, webResponse);
		
		return actionResponse;
	}
		
	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse startDumpKeys(HttpServletRequest req)
	{

		final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
		final String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		final String filePath = "warmer/keys_" + logTimeDateFormat.format(new Date()) + ".txt";
		Runnable r = new Runnable() {
			
			public void run() {
				
				File outputDir = new File(frontcacheHome);
				File keysDumpFile = new  File(outputDir, filePath);
				FileOutputStream fos = null;
				try {
					fos = new FileOutputStream(keysDumpFile);
					for (String url : CacheManager.getInstance().getCachedKeys())
					{
						fos.write((url + "\n").getBytes());
						fos.flush();
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try {
						if (null != fos)
							fos.close(); 
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
			}
		};
		
		Thread t = new Thread(r);
		t.start();				

		DumpKeysActionResponse aResponse = new DumpKeysActionResponse();
		aResponse.setOutputFile(filePath);
			
		return aResponse;
	}
	
	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse reload(HttpServletRequest req)
	{
		FrontCacheEngine.reload();
		
		ActionResponse aResponse = new ReloadActionResponse();
		return aResponse;
	}
	
	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse reloadFallbacks(HttpServletRequest req)
	{
		FallbackResolverFactory.destroy();
		FallbackResolverFactory.init(FrontCacheEngine.getFrontCache().getHttpClient());
		
		ActionResponse aResponse = new ReloadFallbacksActionResponse();
		return aResponse;
	}
	
	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse getFallbackConfigs(HttpServletRequest req)
	{
		FallbackResolverFactory.init(FrontCacheEngine.getFrontCache().getHttpClient());
		List<FallbackConfigEntry> fallbackConfigs = FallbackResolverFactory.getInstance().getFallbackConfigs();
		
		GetFallbackConfigActionResponse aResponse = new GetFallbackConfigActionResponse();
		aResponse.setFallbackConfigs(fallbackConfigs);
		
		return aResponse;
	}

	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse getBots(HttpServletRequest req)
	{
		List<String> bots = new ArrayList<String>();
		bots.addAll(FCConfig.getBotUserAgentKeywords());
		GetBotsActionResponse aResponse = new GetBotsActionResponse();
		aResponse.setBots(bots);
		
		return aResponse;
	}
	
	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse getDynamicURLs(HttpServletRequest req)
	{
		List<String> dynamicURLs = new ArrayList<String>();
		dynamicURLs.addAll(FCConfig.getDynamicURLs());
		GetDynamicURLsActionResponse aResponse = new GetDynamicURLsActionResponse();
		aResponse.setDynamicURLs(dynamicURLs);
		
		return aResponse;
	}
	
	/**
	 * 
	 */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		process(req, resp);
		return;
	}
	
}
