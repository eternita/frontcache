package org.frontcache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import org.frontcache.io.ActionResponse;
import org.frontcache.io.CacheStatusActionResponse;
import org.frontcache.io.CachedKeysActionResponse;
import org.frontcache.io.DummyActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
import org.frontcache.io.InvalidateActionResponse;
import org.frontcache.io.DumpKeysActionResponse;
import org.frontcache.io.PutToCacheActionResponse;
import org.frontcache.io.ReloadActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FrontCacheIOServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	protected Logger logger = LoggerFactory.getLogger(getClass());
	private ObjectMapper jsonMapper = new ObjectMapper();
	
	FrontCacheEngine fcEngine = null;
	
	public FrontCacheIOServlet() {
	}
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		fcEngine = FrontCacheEngine.getFrontCache();
		
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
		String action = req.getParameter("action");
		if (null == action)
			action = "";
		
		ActionResponse aResponse = null;
		switch (action)
		{
		case "invalidate":
			aResponse = invalidate(req);
			break;
			
		case "get-cache-state":
			aResponse = getCacheStatus(req);
			break;
			
		case "get-cached-keys":
			aResponse = getCachedKeys(req);
			break;
			
		case "get-from-cache":
			aResponse = getFromCache(req);
			break;
			
		case "put-to-cache":
			aResponse = putToCache(req);
			break;
			
		case "dump-keys":
			aResponse = startDumpKeys(req);
			break;
			
		case "reload":
			aResponse = reload(req);
			break;
			
			default:
				aResponse = new DummyActionResponse();
			
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
	private ActionResponse getCachedKeys(HttpServletRequest req)
	{
		List<String> keys = CacheManager.getInstance().getCachedKeys();
		ActionResponse aResponse = new CachedKeysActionResponse(keys);
			
		return aResponse;
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
	 * @param req
	 * @return
	 */
	private ActionResponse putToCache(HttpServletRequest req)
	{
		ActionResponse actionResponse = new PutToCacheActionResponse();
		String key = req.getParameter("key");
		String webResponseJSONStr = req.getParameter("webResponseJSON");
		if (null == webResponseJSONStr || null == key)
		{
			actionResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			actionResponse.setErrorDescription("some parameters are null");
			return actionResponse;
		}
		
		WebResponse webResponse = null;
		try {
			webResponse = jsonMapper.readValue(webResponseJSONStr.getBytes(), WebResponse.class);
		} catch (IOException e) {
			e.printStackTrace();
			actionResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			actionResponse.setErrorDescription(e.getMessage());
			return actionResponse;
		}
		if (null == webResponse)
		{
			actionResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			return actionResponse;
		}
			
		if (null == webResponse.getUrl())
		{
			actionResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			actionResponse.setErrorDescription("null = webResponse.getUrl()");
			return actionResponse;
		}
		
		if (null != CacheManager.getInstance().getFromCache(key))
			actionResponse.setErrorDescription("key is already in cache " + key);

		CacheManager.getInstance().putToCache(key, webResponse);
		
		return actionResponse;
	}
	
	/**
	 * 
	 * @param req
	 * @return
	 */
	private ActionResponse startDumpKeys(HttpServletRequest req)
	{

		Runnable r = new Runnable() {
			
			public void run() {
				
				final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
				String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
				File outputDir = new File(frontcacheHome);
				File keysDumpFile = new  File(outputDir, "warmer/keys_" + logTimeDateFormat.format(new Date()) + ".txt");
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

		ActionResponse aResponse = new DumpKeysActionResponse();
			
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
	 */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		process(req, resp);
		return;
	}
	
}
