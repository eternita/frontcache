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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import org.frontcache.bots.BotClassifier;
import org.frontcache.bots.BotRule;
import org.frontcache.bots.BotRuleEntry;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.core.FcNodeStats;
import org.frontcache.core.ApiKey;
import org.frontcache.core.WebResponse;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEntry;
import org.frontcache.license.FcLicense;
import org.frontcache.license.LicenseMonitor;
import org.frontcache.license.LicenseStatus;
import org.frontcache.license.UsageInterval;
import org.frontcache.license.UsageLog;
import org.frontcache.resilience.FcResilienceReload;
import org.frontcache.resilience.fr.FallbackConfigEntry;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.frontcache.warmer.CacheWarmer;
import org.frontcache.warmer.WarmerConfig;
import org.frontcache.warmer.WarmerRunParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FrontCacheIOServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	protected Logger logger = LoggerFactory.getLogger(getClass());
	private ObjectMapper jsonMapper = new ObjectMapper();

	private static final String RESILIENCE_CONFIG_FILE = "resilience.properties";
	private static final String GUARD_RULES_CONFIG_FILE = "guard-rules.conf";
	private static final String BOTS_CONFIG_FILE = "bots.conf";
	private static final String DYNAMIC_URLS_CONFIG_FILE = "dynamic-urls.conf";
	private static final String FALLBACKS_CONFIG_FILE = "fallbacks.conf";

	FrontCacheEngine fcEngine = null;

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

		// API-key validation is active only when an API key is configured, and is THE access control
		// here - restricting which connector answers management traffic is the reverse proxy's job.
		// Shared with /fc-metrics via ApiKey rather than reimplemented, and read per request so that
		// reloading a node's configuration changes the key actually enforced.
		if (!ApiKey.isAuthorized(request))
		{
			response.getOutputStream().write(jsonMapper.writeValueAsBytes(new AccessDeniedActionResponse()));
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

		case FrontcacheAction.GET_NODE_STATS:
			aResponse = getNodeStats(request);
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

		case FrontcacheAction.GET_FALLBACKS_CONFIG:
			aResponse = getFallbacksConfig(request);
			break;

		case FrontcacheAction.GET_BOTS:
			aResponse = getBots(request);
			break;

		case FrontcacheAction.GET_BOTS_CONFIG:
			aResponse = getBotsConfig(request);
			break;

		case FrontcacheAction.RELOAD_BOTS:
			aResponse = reloadBots(request);
			break;

		case FrontcacheAction.GET_DYNAMIC_URLS:
			aResponse = getDynamicURLs(request);
			break;

		case FrontcacheAction.GET_DYNAMIC_URLS_CONFIG:
			aResponse = getDynamicURLsConfig(request);
			break;

		case FrontcacheAction.GET_RESILIENCE_CONFIGS:
		case FrontcacheAction.GET_HYSTRIX_CONFIGS: // pre-2.7 alias, see FrontcacheAction
			aResponse = getResilienceConfigs(request);
			break;

		case FrontcacheAction.GET_GUARD_RULES:
			aResponse = getGuardRules(request);
			break;

		case FrontcacheAction.GET_GUARD_CONFIG:
			aResponse = getGuardConfig(request);
			break;

		case FrontcacheAction.RELOAD_GUARD_RULES:
			aResponse = reloadGuardRules(request);
			break;

		case FrontcacheAction.SET_CONFIG:
			aResponse = setConfig(request);
			break;

		case FrontcacheAction.RELOAD_RESILIENCE:
			aResponse = reloadResilience(request);
			break;

		case FrontcacheAction.RELOAD_DYNAMIC_URLS:
			aResponse = reloadDynamicURLs(request);
			break;

		case FrontcacheAction.GET_LICENSE:
			aResponse = licenseResponse(FrontcacheAction.GET_LICENSE, FcLicense.current());
			break;

		case FrontcacheAction.RELOAD_LICENSE:
			aResponse = licenseResponse(FrontcacheAction.RELOAD_LICENSE, FcLicense.reload());
			break;

		case FrontcacheAction.GET_USAGE_REPORT:
			aResponse = getUsageReport(request);
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

		case FrontcacheAction.WARMER_STATUS:
		case FrontcacheAction.WARMER_LISTS:
		case FrontcacheAction.GET_WARMER_LIST:
		case FrontcacheAction.PUT_WARMER_LIST:
		case FrontcacheAction.DELETE_WARMER_LIST:
		case FrontcacheAction.WARMER_PREFLIGHT:
		case FrontcacheAction.WARMER_START:
		case FrontcacheAction.WARMER_UPDATE:
		case FrontcacheAction.WARMER_PAUSE:
		case FrontcacheAction.WARMER_RESUME:
		case FrontcacheAction.WARMER_STOP:
			aResponse = warmer(action, request, response);
			if (null == aResponse)
				return; // streamed
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

		if ("*".equals(filter))
		{
			// "*" empties this node - including entries pushed in by index replication, which used to
			// survive it because they carry the SOURCE edge's domain and the delete was domain-scoped.
			CacheManager.getInstance().removeFromCacheAll();
			logger.info("Invalidation for filter: {} (whole node)", filter);

			return aResponse;
		}

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
	 * The pollable view - see {@link NodeStatsActionResponse}.
	 *
	 * Reads through {@link CacheManager#existingInstance()}, not {@code getInstance()}: this action is
	 * on a console's poll loop, and a node whose cache has not started yet must not have it started BY
	 * being polled - that would open the Lucene index and take its write lock. The same rule the
	 * metrics exporter follows. With no cache yet, the cache numbers report as unknown and the JVM
	 * numbers still answer.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getNodeStats(HttpServletRequest req)
	{
		CacheProcessor processor = CacheManager.existingInstance();
		CacheStats cacheStats = (null == processor) ? CacheStats.NOT_TRACKED : processor.getCacheStats();

		return new NodeStatsActionResponse(cacheStats, FcNodeStats.current());
	}

	/**
	 * {@code get-license} / {@code reload-license} - see {@link LicenseActionResponse}.
	 *
	 * The engine is asked for its domain through {@link FrontCacheEngine#existingInstance()}: answering a
	 * console's poll must not be what builds an engine, the {@code get-node-stats} rule.
	 */
	private ActionResponse licenseResponse(String action, LicenseStatus status)
	{
		FrontCacheEngine engine = FrontCacheEngine.existingInstance();

		return new LicenseActionResponse(action, status, FcLicense.featuresInUse(), LicenseMonitor.instance(),
				(null == engine) ? null : engine.getPublicDomain());
	}

	/**
	 * {@code get-usage-report} - this node's usage log as intervals, {@code from}..{@code to} (yyyy-MM),
	 * the last twelve months by default. Twenty-four at most: the log keeps {@link UsageLog#KEEP_MONTHS}.
	 */
	private ActionResponse getUsageReport(HttpServletRequest req)
	{
		YearMonth now = YearMonth.now(ZoneOffset.UTC);
		YearMonth to = parseMonth(req.getParameter("to"), now);
		YearMonth from = parseMonth(req.getParameter("from"), to.minusMonths(11));

		if (from.isAfter(to))
			from = to;
		if (from.isBefore(to.minusMonths(UsageLog.KEEP_MONTHS - 1)))
			from = to.minusMonths(UsageLog.KEEP_MONTHS - 1);

		List<UsageInterval> intervals = LicenseMonitor.usageIntervals(from, to);

		return new UsageReportActionResponse(from.toString(), to.toString(), LicenseMonitor.instance().getId(),
				intervals);
	}

	private static YearMonth parseMonth(String value, YearMonth fallback)
	{
		if (null == value || value.isBlank())
			return fallback;
		try {
			return YearMonth.parse(value.trim());
		} catch (Exception e) {
			return fallback;
		}
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
		String webResponseJson = req.getParameter("webResponse");

		if (key == null || webResponseJson == null)
		{
			PutToCacheActionResponse aResponse = new PutToCacheActionResponse(key);
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription("Required parameters: key, webResponse");
			return aResponse;
		}

		// A `domain` parameter from an older client is accepted and ignored - entries are not
		// domain-scoped any more, so there is nothing to map it onto.
		PutToCacheActionResponse aResponse = new PutToCacheActionResponse(key);

		try
		{
			WebResponse webResponse = jsonMapper.readValue(webResponseJson, WebResponse.class);
			CacheManager.getInstance().putToCache(key, webResponse);
			logger.info("put-to-cache: key={}", key);
		}
		catch (Exception e)
		{
			logger.error("put-to-cache failed: key={}", key, e);
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(e.getMessage());
		}

		return aResponse;
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
		FallbackResolverFactory.init(FrontCacheEngine.getFrontCache().getFcHttpClient());

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
		FallbackResolverFactory.init(FrontCacheEngine.getFrontCache().getFcHttpClient());

		GetFallbackConfigActionResponse aResponse = new GetFallbackConfigActionResponse();
		aResponse.setFallbackEntries(FallbackResolverFactory.getInstance().getFallbackEntries());

		return aResponse;
	}

	/**
	 * Bot keywords, as a flat set. The console aggregates several edges, so it keys these by EDGE - the
	 * per-domain keying this used to carry was a leftover of multi-domain support and always held one
	 * entry. The response still emits the legacy map alongside for an older console; see
	 * {@link LegacyDomainKeyedField}.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getBots(HttpServletRequest req)
	{
		BotClassifier classifier = BotClassifier.getInstance();

		List<BotRuleEntry> botRules = new ArrayList<BotRuleEntry>();
		for (BotRule rule : classifier.getRules())
			botRules.add(rule.toEntry());

		GetBotsActionResponse aResponse = new GetBotsActionResponse();
		// The bare keywords keep their own field and their pre-2.9.0 meaning - a console older than
		// the rules renders that one and must not suddenly see rule names in it.
		aResponse.setBotKeywords(classifier.getKeywords());
		aResponse.setBotRules(botRules);

		return aResponse;
	}

	/**
	 * Reads the raw ./conf/bots.conf file (comments and empty lines preserved) so it can be displayed
	 * as-is in the console, next to the parsed view {@link #getBots} serves.
	 *
	 * Worth having both: the parsed view shows what is deciding, this one shows what the operator
	 * wrote. A line the parser skipped - a commented-out rule, a typo that made it a bare keyword -
	 * is visible here and nowhere else.
	 *
	 * The tolerant read matters as much as it does for guard-rules.conf: conf/bots.conf is optional
	 * and {@code FCConfig.getConfigInputStream} signals a missing file by throwing, so a node without
	 * one has to produce the "not found" response rather than a failed action.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getBotsConfig(HttpServletRequest req)
	{
		GetBotsConfigActionResponse aResponse = new GetBotsConfigActionResponse();

		String raw = readConfigFile(BOTS_CONFIG_FILE);

		if (null == raw)
		{
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(BOTS_CONFIG_FILE + " not found");
		} else {
			aResponse.setBotsConfig(raw);
		}

		return aResponse;
	}

	/**
	 * Re-reads conf/bots.conf without restarting the node. Rules are rebuilt, so hit counters start
	 * from zero again - the same contract as reload-guard-rules.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse reloadBots(HttpServletRequest req)
	{
		BotClassifier.reload();

		return new ReloadBotsActionResponse();
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
	 * Dynamic-URL patterns, keyed by domain for the console - see {@link #getBots}. One entry.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getDynamicURLs(HttpServletRequest req)
	{
		GetDynamicURLsActionResponse aResponse = new GetDynamicURLsActionResponse();
		aResponse.setDynamicUrlPatterns(FCConfig.getDynamicURLs());

		return aResponse;
	}

	/**
	 * Reads the raw ./conf/fallbacks.conf file (comments and empty lines preserved) so it can be
	 * displayed as-is in the console, where {@link #getFallbackConfigs} used to supply the parsed
	 * entries.
	 *
	 * Same tolerant read as the other raw-file actions: the file is optional and
	 * {@code FCConfig.getConfigInputStream} signals a missing one by throwing.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getFallbacksConfig(HttpServletRequest req)
	{
		GetFallbacksConfigActionResponse aResponse = new GetFallbacksConfigActionResponse();

		String raw = readConfigFile(FALLBACKS_CONFIG_FILE);

		if (null == raw)
		{
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(FALLBACKS_CONFIG_FILE + " not found");
		} else {
			aResponse.setFallbacksConfig(raw);
		}

		return aResponse;
	}

	/**
	 * Reads the raw ./conf/dynamic-urls.conf file (comments and empty lines preserved) so it can be
	 * displayed as-is in the console, where {@link #getDynamicURLs} used to supply a bare list of the
	 * patterns parsed out of it.
	 *
	 * Same tolerant read as the other two raw-file actions: the file is optional and
	 * {@code FCConfig.getConfigInputStream} signals a missing one by throwing.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getDynamicURLsConfig(HttpServletRequest req)
	{
		GetDynamicURLsConfigActionResponse aResponse = new GetDynamicURLsConfigActionResponse();

		String raw = readConfigFile(DYNAMIC_URLS_CONFIG_FILE);

		if (null == raw)
		{
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(DYNAMIC_URLS_CONFIG_FILE + " not found");
		} else {
			aResponse.setDynamicURLsConfig(raw);
		}

		return aResponse;
	}

	/**
	 * Reads the raw ./conf/resilience.properties file (comments and empty lines preserved) so it can be
	 * displayed as-is in the console.
	 *
	 * The pre-2.7 ./conf/hystrix.properties fallback that used to be here is gone, in step with
	 * {@code FcResilienceConfig}, and the two must stay in step: this action shows the operator the file
	 * the node is actually using, so reading a different one than the engine reads would make the console
	 * lie in exactly the situation - a half-finished upgrade - where it is being consulted.
	 *
	 * Answers both {@code get-resilience-configs} and the pre-2.7 {@code get-hystrix-configs}, and the
	 * response carries the value under both field names - the console is a separate deployment, so both
	 * directions of version skew have to work.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getResilienceConfigs(HttpServletRequest req)
	{
		GetResilienceConfigActionResponse aResponse = new GetResilienceConfigActionResponse();

		String raw = readConfigFile(RESILIENCE_CONFIG_FILE);

		if (null == raw)
		{
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(RESILIENCE_CONFIG_FILE + " not found");
		} else {
			aResponse.setResilienceConfig(raw);
		}

		return aResponse;
	}

	/**
	 * Writes one config file and applies it - see {@link FrontcacheAction#SET_CONFIG}.
	 *
	 * Parameter extraction only; {@link ConfigWriteAction} is the operation, so it can be tested
	 * without a servlet container or a live engine. The one thing decided here is the authorization,
	 * because it is the only part that needs the request: {@code isAuthorizedToWrite} is stricter than
	 * the check {@link #process} already ran, which lets an un-keyed node through.
	 */
	private ActionResponse setConfig(HttpServletRequest req)
	{
		String applyParam = req.getParameter("apply");
		boolean apply = (null == applyParam) || Boolean.parseBoolean(applyParam);

		return ConfigWriteAction.perform(
				req.getParameter("file"),
				req.getParameter("content"),
				req.getParameter("baseHash"),
				apply,
				ApiKey.isAuthorizedToWrite(req));
	}

	private ActionResponse reloadResilience(HttpServletRequest req)
	{
		ReloadResilienceActionResponse aResponse = new ReloadResilienceActionResponse();
		aResponse.setWarnings(new ArrayList<String>(FcResilienceReload.reload().warnings()));
		return aResponse;
	}

	private ActionResponse reloadDynamicURLs(HttpServletRequest req)
	{
		ReloadDynamicUrlsActionResponse aResponse = new ReloadDynamicUrlsActionResponse();
		aResponse.setPatternCount(FCConfig.reloadDynamicURLs());
		return aResponse;
	}

	/**
	 * @return the file's contents, or null when it is not there or cannot be read.
	 *
	 * The catch is Exception and not IOException on purpose: {@code FCConfig.getConfigInputStream}
	 * signals "nowhere on disk or classpath" by THROWING a RuntimeException, not by returning null. With
	 * a narrower catch, a missing conf/resilience.properties escaped this method before the legacy file
	 * name was ever tried, and the whole action failed - which is exactly what happens on a node still
	 * running the pre-2.7 file name, i.e. every node mid-upgrade. Same trap, and same fix, as
	 * {@code FcResilienceConfig.read}.
	 */
	private String readConfigFile(String fileName)
	{
		try (InputStream is = FCConfig.getConfigInputStream(fileName))
		{
			return (null == is) ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);

		} catch (Exception e) {
			logger.debug("Can't read " + fileName, e);
			return null;
		}
	}

	/**
	 * Reads the raw ./conf/guard-rules.conf file (comments and empty lines
	 * preserved) so it can be displayed as-is in the console.
	 *
	 * @param req
	 * @return
	 */
	private ActionResponse getGuardConfig(HttpServletRequest req)
	{
		GetGuardConfigActionResponse aResponse = new GetGuardConfigActionResponse();

		// Same tolerant read as the resilience config above. This method used to catch only IOException,
		// so on a node with no conf/guard-rules.conf - which is optional, and absent on both e2e nodes -
		// the RuntimeException from getConfigInputStream escaped the action entirely instead of producing
		// the "not found" response the branch below was written for.
		String raw = readConfigFile(GUARD_RULES_CONFIG_FILE);

		if (null == raw)
		{
			aResponse.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			aResponse.setErrorDescription(GUARD_RULES_CONFIG_FILE + " not found");
		} else {
			aResponse.setGuardConfig(raw);
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
	 * The cache warmer's actions - see {@link WarmerActions}, which holds the gates. Parameter
	 * extraction only, plus the one thing only the request can answer: {@code ApiKey.isAuthorizedToWrite}.
	 *
	 * {@code put-warmer-list} carries the list as the request BODY. {@code getParameter} on a
	 * non-form POST reads the query string only and leaves the body unread, which is what lets it be
	 * streamed into the store here.
	 *
	 * @return the response to write, or null when the answer was streamed ({@code raw=true})
	 */
	private ActionResponse warmer(String action, HttpServletRequest req, HttpServletResponse resp) throws IOException
	{
		CacheWarmer warmer;
		try {
			warmer = CacheWarmer.getInstance();
		} catch (Exception e) {
			logger.warn("cache warmer unavailable", e);
			ActionResponse unavailable = new WarmerStatusActionResponse(action);
			unavailable.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
			unavailable.setErrorDescription("the cache warmer is unavailable on this node: " + e.getMessage());
			return unavailable;
		}

		// the default key base follows the scheme the management request arrived with - see
		// CacheWarmer.defaultKeyBase(WarmerConfig, boolean)
		return WarmerActions.withRequestDefaults(warmerAction(action, warmer, req, resp), warmer.config(), req.isSecure());
	}

	private ActionResponse warmerAction(String action, CacheWarmer warmer, HttpServletRequest req, HttpServletResponse resp) throws IOException
	{
		boolean authorizedToWrite = ApiKey.isAuthorizedToWrite(req);

		switch (action)
		{
		case FrontcacheAction.WARMER_STATUS:
			return WarmerActions.status(warmer);

		case FrontcacheAction.WARMER_LISTS:
			return WarmerActions.lists(warmer);

		case FrontcacheAction.GET_WARMER_LIST:
			if ("true".equalsIgnoreCase(req.getParameter("raw")))
				return streamWarmerList(warmer, req.getParameter("name"), resp);
			return WarmerActions.getList(warmer, req.getParameter("name"), req.getParameter("grep"));

		case FrontcacheAction.PUT_WARMER_LIST:
			return WarmerActions.putList(warmer, req.getParameter("name"), req.getInputStream(),
					req.getParameter("baseHash"), authorizedToWrite);

		case FrontcacheAction.DELETE_WARMER_LIST:
			return WarmerActions.deleteList(warmer, req.getParameter("name"), authorizedToWrite);

		case FrontcacheAction.WARMER_UPDATE:
			try {
				return WarmerActions.update(warmer, optionalLong(req, "delayMs"), optionalInt(req, "concurrency"),
						authorizedToWrite);
			} catch (NumberFormatException e) {
				return new WarmerStatusActionResponse(action).refuse("delayMs and concurrency must be numbers");
			}

		case FrontcacheAction.WARMER_PAUSE:
			return WarmerActions.pause(warmer, authorizedToWrite);

		case FrontcacheAction.WARMER_RESUME:
			return WarmerActions.resume(warmer, authorizedToWrite);

		case FrontcacheAction.WARMER_STOP:
			return WarmerActions.stop(warmer, authorizedToWrite);

		default: // preflight and start take the same parameters
			WarmerRunParams params;
			try {
				params = warmRunParams(req, warmer.config());
			} catch (NumberFormatException e) {
				if (FrontcacheAction.WARMER_PREFLIGHT.equals(action))
					return new WarmerPreflightActionResponse(action).refuse("delayMs, concurrency and resumeFromLine must be numbers");
				return new WarmerStatusActionResponse(action).refuse("delayMs, concurrency and resumeFromLine must be numbers");
			}

			if (FrontcacheAction.WARMER_PREFLIGHT.equals(action))
				return WarmerActions.preflight(warmer, params, req.isSecure(), req.getLocalPort(), CacheManager.existingInstance());

			return WarmerActions.start(warmer, params, req.isSecure(), req.getLocalPort(), authorizedToWrite);
		}
	}

	private static WarmerRunParams warmRunParams(HttpServletRequest req, WarmerConfig config)
	{
		WarmerRunParams params = new WarmerRunParams();
		params.setList(req.getParameter("list"));
		params.setMode(req.getParameter("mode"));
		params.setKeyBase(req.getParameter("keyBase"));
		params.setClientType(req.getParameter("clientType"));

		Long delayMs = optionalLong(req, "delayMs");
		params.setDelayMs(null == delayMs ? config.getDefaultDelayMs() : delayMs);

		Integer concurrency = optionalInt(req, "concurrency");
		params.setConcurrency(null == concurrency ? 1 : concurrency);

		Long resumeFromLine = optionalLong(req, "resumeFromLine");
		params.setResumeFromLine(null == resumeFromLine ? 0 : resumeFromLine);

		return params;
	}

	private static Long optionalLong(HttpServletRequest req, String name)
	{
		String value = req.getParameter(name);
		return (null == value || value.trim().isEmpty()) ? null : Long.valueOf(value.trim());
	}

	private static Integer optionalInt(HttpServletRequest req, String name)
	{
		String value = req.getParameter(name);
		return (null == value || value.trim().isEmpty()) ? null : Integer.valueOf(value.trim());
	}

	/** {@code get-warmer-list&raw=true}: the file as a download, streamed. A refusal is still JSON. */
	private ActionResponse streamWarmerList(CacheWarmer warmer, String name, HttpServletResponse resp) throws IOException
	{
		String problem = WarmerActions.rawListProblem(warmer, name);
		if (null != problem)
			return new WarmerListActionResponse(FrontcacheAction.GET_WARMER_LIST).refuse(problem);

		resp.setContentType("text/plain; charset=UTF-8");
		// the name is safe to quote as-is: WarmerListStore.NAME admits no quote, space or separator
		resp.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
		try (InputStream in = warmer.getStore().openRaw(name); OutputStream out = resp.getOutputStream())
		{
			in.transferTo(out);
		}
		return null;
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
