/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.frontcache.cache.CacheManager;
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEntry;
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
	private String siteKey = "";

	// guards against concurrent backups - only one index backup may run at a time
	private final AtomicBoolean backupInProgress = new AtomicBoolean(false);

	// guards against concurrent key dumps - only one dump-keys may run at a time
	private final AtomicBoolean dumpKeysInProgress = new AtomicBoolean(false);


	public FrontCacheIOServlet() {
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		fcEngine = FrontCacheEngine.getFrontCache();

		String managementPortStr = FCConfig.getProperty("front-cache.management.port");
		siteKey = FCConfig.getProperty("front-cache.site-key", "");
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

		// site-key validation is active only when a site key is configured.
		// A request is authorized when its X_FRONTCACHE_SITE_KEY header matches the
		// global site key or any per-domain site key (the management API is multi-domain aware).
		if (siteKey.length() > 0)
		{
			String requestSiteKey = request.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY);
			boolean authorized = null != requestSiteKey && requestSiteKey.length() > 0
					&& (siteKey.equals(requestSiteKey) || null != fcEngine.getDomainContexBySiteKey(requestSiteKey));
			if (!authorized)
			{
				response.getOutputStream().write(jsonMapper.writeValueAsBytes(new AccessDeniedActionResponse(managementPort)));
				return;
			}
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

		case FrontcacheAction.PUT_TO_CACHE:
			aResponse = putToCache(request);
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

		case FrontcacheAction.GET_HYSTRIX_CONFIGS:
			aResponse = getHystrixConfigs(request);
			break;

		case FrontcacheAction.GET_GUARD_RULES:
			aResponse = getGuardRules(request);
			break;

		case FrontcacheAction.RELOAD_GUARD_RULES:
			aResponse = reloadGuardRules(request);
			break;

		case FrontcacheAction.PATCH:
			aResponse = patch(request);
			break;

		case FrontcacheAction.PURGE:
			aResponse = purge(request);
			break;

		case FrontcacheAction.BACKUP_INDEX:
			aResponse = backupIndex(request);
			break;

		case FrontcacheAction.HELP:
			aResponse = new HelpActionResponse(FrontcacheAction.actionsDescriptionMap);
			break;

		default:
			aResponse = new PingActionResponse();
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

		String siteKey = req.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY);
		String domain = null;

		FrontCacheEngine fcEngine = FrontCacheEngine.getFrontCache();
		DomainContext domainContext = fcEngine.getDomainContexBySiteKey(siteKey);
		if (null != domainContext)
			domain = domainContext.getDomain();

		if ("*".equals(filter))
		{
			// A domain-scoped delete only drops entries stamped with this node's domain. Entries
			// pushed in by index replication carry the SOURCE edge's domain, so they survive every
			// "*" and keep being served (reads are by URL only, domain is not consulted). On a
			// single-domain node "*" can only mean "empty this node", so do exactly that; a
			// multi-domain node keeps the per-domain behaviour unless all-domains=true is passed.
			boolean allDomains = "true".equalsIgnoreCase(req.getParameter("all-domains"))
					|| fcEngine.getConfiguredDomains().size() < 2;

			if (allDomains)
			{
				CacheManager.getInstance().removeFromCacheAll();
				logger.info("Invalidation for filter: {} (all domains)", filter);
			} else {
				CacheManager.getInstance().removeFromCacheAll(domain);
				logger.info("Invalidation for filter: {} (domain: {})", filter, domain);
			}

			return aResponse;
		}

		CacheManager.getInstance().removeFromCache(domain, filter);

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

		WebResponse webResponse = CacheManager.getInstance().getFromCache(key, null); // admin lookup - no request context
		GetFromCacheActionResponse actionResponse = new GetFromCacheActionResponse(key, webResponse);

		return actionResponse;
	}

	/**
	 * Stores a WebResponse entry into the local cache.
	 * Used by the index-replication feature to push entries from a source edge
	 * to a target edge.
	 *
	 * @param req must carry {@code key}, {@code domain}, and {@code webResponse} (JSON) parameters
	 * @return PutToCacheActionResponse with OK or ERROR status
	 */
	private ActionResponse putToCache(HttpServletRequest req)
	{
		String key = req.getParameter("key");
		String requestedDomain = req.getParameter("domain");
		String webResponseJson = req.getParameter("webResponse");

		if (key == null || requestedDomain == null || webResponseJson == null)
		{
			PutToCacheActionResponse aResponse = new PutToCacheActionResponse(key, requestedDomain);
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription("Required parameters: key, domain, webResponse");
			return aResponse;
		}

		String domain = resolveLocalDomain(req, requestedDomain);
		PutToCacheActionResponse aResponse = new PutToCacheActionResponse(key, domain);

		try
		{
			WebResponse webResponse = jsonMapper.readValue(webResponseJson, WebResponse.class);
			CacheManager.getInstance().putToCache(domain, key, webResponse);
			logger.info("put-to-cache: domain={}, key={}", domain, key);
		}
		catch (Exception e)
		{
			logger.error("put-to-cache failed: domain={}, key={}", domain, key, e);
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(e.getMessage());
		}

		return aResponse;
	}

	/**
	 * Maps the domain a caller asked for onto a domain THIS node serves.
	 *
	 * Replication sends the source entry's domain, so an entry copied from another edge would be
	 * stored under a domain this node knows nothing about - invisible to "invalidate all" and to the
	 * per-domain counters, though still served (lookups are by URL). A requested domain this node
	 * actually serves is kept as-is, so a multi-domain edge can still replicate per domain.
	 *
	 * @param requestedDomain domain from the request (never null here)
	 * @return the domain to store the entry under
	 */
	private String resolveLocalDomain(HttpServletRequest req, String requestedDomain)
	{
		FrontCacheEngine fcEngine = FrontCacheEngine.getFrontCache();

		if (fcEngine.isConfiguredDomain(requestedDomain))
			return requestedDomain;

		DomainContext domainContext = fcEngine.getDomainContexBySiteKey(req.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY));
		if (null == domainContext || null == domainContext.getDomain())
			return requestedDomain; // nothing better to map onto - keep the caller's value

		logger.debug("put-to-cache: domain {} is not served here - storing under {}",
				requestedDomain, domainContext.getDomain());

		return domainContext.getDomain();
	}

	/**
	 * Dumps cached keys to a file under ./warmer asynchronously.
	 * <p>
	 * Only one dump may run at a time - if a dump is already in progress the request is
	 * rejected and no new dump is started.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse startDumpKeys(HttpServletRequest req)
	{
		// reject if a dump is already running (only flip false -> true wins)
		if (!dumpKeysInProgress.compareAndSet(false, true))
		{
			DumpKeysActionResponse aResponse = new DumpKeysActionResponse();
			aResponse.setAlreadyInProgress();
			return aResponse;
		}

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
					dumpKeysInProgress.set(false);
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
		Map <String, Set<FallbackConfigEntry>> fallbackConfigs = FallbackResolverFactory.getInstance().getFallbackConfigs();

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
		Map<String, Set<String>> bots = new HashMap<String, Set<String>>();
		bots.putAll(FCConfig.getBotUserAgentKeywords());
		GetBotsActionResponse aResponse = new GetBotsActionResponse();
		aResponse.setBots(bots);

		return aResponse;
	}

	/**
	 * Guard rules in evaluation order (built-ins first, then conf/guard-rules.conf), each with the
	 * number of requests it matched since start - which is how you tell a rule that is doing work
	 * from one that can be deleted.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getGuardRules(HttpServletRequest req)
	{
		List<GuardRuleEntry> guardRules = new ArrayList<GuardRuleEntry>();
		for (GuardRule rule : FrontCacheEngine.getFrontCache().getGuardRuleEngine().getRules())
			guardRules.add(rule.toEntry());

		GetGuardRulesActionResponse aResponse = new GetGuardRulesActionResponse();
		aResponse.setGuardRules(guardRules);

		return aResponse;
	}

	/**
	 * Re-reads conf/guard-rules.conf without restarting the node. Rules are rebuilt, so hit counters
	 * start from zero again.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse reloadGuardRules(HttpServletRequest req)
	{
		FrontCacheEngine.getFrontCache().getGuardRuleEngine().reload();

		ActionResponse aResponse = new ReloadGuardRulesActionResponse();
		return aResponse;
	}

	/**
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getDynamicURLs(HttpServletRequest req)
	{
		Map<String, Set<String>> dynamicURLs = new HashMap<String, Set<String>>();
		dynamicURLs.putAll(FCConfig.getDynamicURLs());
		GetDynamicURLsActionResponse aResponse = new GetDynamicURLsActionResponse();
		aResponse.setDynamicURLs(dynamicURLs);

		return aResponse;
	}

	/**
	 * Reads the raw ./conf/hystrix.properties file (comments and empty lines
	 * preserved) so it can be displayed as-is in the console.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getHystrixConfigs(HttpServletRequest req)
	{
		GetHystrixConfigActionResponse aResponse = new GetHystrixConfigActionResponse();

		try (InputStream is = FCConfig.getConfigInputStream("hystrix.properties"))
		{
			if (null == is)
			{
				aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
				aResponse.setErrorDescription("hystrix.properties not found");
			} else {
				aResponse.setHystrixConfig(new String(is.readAllBytes(), StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			logger.error("Can't read hystrix.properties", e);
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(e.getMessage());
		}

		return aResponse;
	}

	/**
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse patch(HttpServletRequest req)
	{

		Runnable r = new Runnable() {
			public void run() {
				CacheManager.getInstance().patch();
			}
		};

		Thread t = new Thread(r);
		t.start();

		PatchActionResponse aResponse = new PatchActionResponse();
		return aResponse;
	}

	/**
	 * Purge expired entries from the cache. Runs asynchronously since it may scan
	 * the whole cache (L1 + L2).
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse purge(HttpServletRequest req)
	{
		Runnable r = new Runnable() {
			public void run() {
				CacheManager.getInstance().purge();
			}
		};

		Thread t = new Thread(r);
		t.start();

		PurgeActionResponse aResponse = new PurgeActionResponse();
		return aResponse;
	}

	/**
	 * Backs up the on-disk (L2) index to a zip archive at the edge. Runs asynchronously
	 * since archiving a large index may take a while; the created archive name is logged at the edge.
	 * <p>
	 * Only one backup may run at a time - if a backup is already in progress the request is
	 * rejected and no new backup is started.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse backupIndex(HttpServletRequest req)
	{
		// reject if a backup is already running (only flip false -> true wins)
		if (!backupInProgress.compareAndSet(false, true))
		{
			BackupActionResponse aResponse = new BackupActionResponse();
			aResponse.setAlreadyInProgress();
			return aResponse;
		}

		Runnable r = new Runnable() {
			public void run() {
				try {
					String backupArchive = CacheManager.getInstance().backup();
					logger.info("index backup completed: {}", backupArchive);
				} catch (Exception e) {
					logger.error("index backup failed", e);
				} finally {
					backupInProgress.set(false);
				}
			}
		};

		Thread t = new Thread(r);
		t.start();

		BackupActionResponse aResponse = new BackupActionResponse();
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
