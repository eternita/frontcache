package org.frontcache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.cache.CacheManager;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackConfigEntry;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.frontcache.io.AccessDeniedActionResponse;
import org.frontcache.io.ActionResponse;
import org.frontcache.io.CacheStatusActionResponse;
import org.frontcache.io.DumpKeysActionResponse;
import org.frontcache.io.GetFallbackConfigActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
import org.frontcache.io.HelpActionResponse;
import org.frontcache.io.InvalidateActionResponse;
import org.frontcache.io.ReloadActionResponse;
import org.frontcache.io.ReloadFallbacksActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FrontCacheIOServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	protected Logger logger = LoggerFactory.getLogger(getClass());
	private ObjectMapper jsonMapper = new ObjectMapper();
	
	FrontCacheEngine fcEngine = null;

	private String managementScheme = null; // management scheme for security

	
	public FrontCacheIOServlet() {
	}
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		fcEngine = FrontCacheEngine.getFrontCache();
		
		managementScheme = FCConfig.getProperty("front-cache.management-scheme");
		if (null == managementScheme)
			logger.warn("Connector Sheme is not configured for Frontcache Management URI. Management URI is accessible for all connectors");
		
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


	private void process(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException 
	{
		if (null != managementScheme && !req.getScheme().equals(managementScheme))
		{
			logger.info("Accessing Management URL with wrong scheme" + req.getScheme());
			resp.getOutputStream().write(jsonMapper.writeValueAsBytes(new AccessDeniedActionResponse(managementScheme)));
			return;
		}
		
		String action = req.getParameter("action");
		if (null == action)
			action = "";
		
		ActionResponse aResponse = null;
		switch (action)
		{
		case FrontcacheAction.INVALIDATE:
			aResponse = invalidate(req);
			break;
			
		case FrontcacheAction.GET_CACHE_STATE:
			aResponse = getCacheStatus(req);
			break;
			
		case FrontcacheAction.GET_CACHED_KEYS:
			getCachedKeys(req, resp);
			return;
			
		case FrontcacheAction.GET_FROM_CACHE:
			aResponse = getFromCache(req);
			break;
			
		case FrontcacheAction.DUMP_KEYS:
			aResponse = startDumpKeys(req);
			break;
			
		case "reload":
			aResponse = reload(req);
			break;

		case FrontcacheAction.RELOAD_FALLBACKS:
			aResponse = reloadFallbacks(req);
			break;
			
		case FrontcacheAction.GET_FALLBACK_CONFIGS:
			aResponse = getFallbackConfigs(req);
			break;
			
			default:
				aResponse = new HelpActionResponse(FrontcacheAction.actionsDescriptionMap);
			
		}
		resp.getOutputStream().write(jsonMapper.writeValueAsBytes(aResponse));
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
	 */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		process(req, resp);
		return;
	}
	
}
