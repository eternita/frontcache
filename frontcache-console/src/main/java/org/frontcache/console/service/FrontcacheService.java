/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.BotConfig;
import org.frontcache.console.model.DynamicURLsConfig;
import org.frontcache.console.model.FallbackConfig;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.model.GuardConfig;
import org.frontcache.console.model.GuardRulesConfig;
import org.frontcache.console.model.ResilienceConfig;
import org.frontcache.console.model.HystrixConnection;
import org.frontcache.core.WebResponse;
import org.frontcache.guard.GuardRuleEntry;
import org.frontcache.resilience.fr.FallbackConfigEntry;
import org.frontcache.io.BackupActionResponse;
import org.frontcache.io.DumpKeysActionResponse;
import org.frontcache.replication.CacheReplicator;
import org.frontcache.replication.ReplicationProgressListener;
import org.frontcache.replication.ReplicationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;

@Service("frontcacheService")
public class FrontcacheService {

	private Config fcConsoleConfig = null;

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);

	private ObjectMapper jsonMapper = new ObjectMapper();

	private Set<String> frontcacheAgentURLs = new LinkedHashSet<String>();

	private String siteKey = "default-site-key";

	private static final int THREAD_AMOUNT = 4;

	private ExecutorService executor = Executors.newFixedThreadPool(THREAD_AMOUNT);

	private static final long FRONTCACHE_CLIENT_TIMEOUT = FrontCacheClient.CONNECTION_TIMEOUT + 1000; // slightly more then frontcache client timeout
	private static final long CONSOLE_TIMEOUT = 1000;


	public FrontcacheService() {
		loadConfigs();
	}

	private void loadConfigs() {

		String frontcacheConsoleConfPath = System.getenv("FRONTCACHE_CONFIG");

		if (null == frontcacheConsoleConfPath)
		{
			// backward compatibility
			frontcacheConsoleConfPath = System.getProperty("org.frontcache.console.config");
		}

		if (null == frontcacheConsoleConfPath)
		{
			logger.info("Neither environment variable 'FRONTCACHE_CONFIG' nor system property 'org.frontcache.console.config' is defined");
			return;
		}

		try
		{
			File configFile = new File(frontcacheConsoleConfPath);
			if (!configFile.exists())
			{
				logger.info("Console config file doesn't exist: " + configFile.getAbsolutePath());
				return;
			}

			fcConsoleConfig = ConfigFactory.parseFile(configFile).getConfig("frontcache").getConfig("console");

		} catch (Exception e) {
			logger.error("Console frontcacheAgentURLs are not loaded from " + frontcacheConsoleConfPath);
			throw new RuntimeException("Can't initialize Frontcache Console", e);
		}

		List<ConfigValue> urls = fcConsoleConfig.getList("urls");
		for (ConfigValue urlValue : urls) {
			String url = urlValue.unwrapped().toString();
			logger.info(" -- Loading url {} from console config", url);
			frontcacheAgentURLs.add(url);
		}
		siteKey = fcConsoleConfig.getString("siteKey");
		logger.info(" -- Loading siteKey {} from console config", siteKey);

	}

	public String getSiteKey(){
		return siteKey;
	}

	public boolean isFrontCacheEdgeAvailable(String frontcacheURL)
	{
		boolean available = false;
		FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL, siteKey);
		if (null != fcClient.getCacheState()) // if get state work -> FC is active
			available = true;

		return available;
	}


	/**
	 *
	 * @return
	 */
	public Map<String, FrontCacheStatus> getClusterStatus() {

        List<Future<FrontCacheStatus>> futureList = new ArrayList<Future<FrontCacheStatus>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order).
		Map<String, FrontCacheStatus> clusterStatus = new LinkedHashMap<String, FrontCacheStatus>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new FrontCacheStatusCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<FrontCacheStatus> f : futureList)
        {
            try {
            	FrontCacheStatus result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result);

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some statuses may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	/**
	 * Cluster statuses as a list in console-config edge order - the order used
	 * by the "Edges status" block across the console pages.
	 */
	public List<FrontCacheStatus> getClusterStatusList() {
		return new ArrayList<FrontCacheStatus>(getClusterStatus().values());
	}


	/**
	 *
	 * @param domain
	 * @return
	 */
	public Map<String, Set<FallbackConfigEntry>> getFallbackConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order) so the UI
		// tabs appear in the configured order.
		Map<String, Set<FallbackConfigEntry>> clusterStatus = new LinkedHashMap<String, Set<FallbackConfigEntry>>();

        List<Future<FallbackConfig>> futureList = new ArrayList<Future<FallbackConfig>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new FallbackConfigsCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<FallbackConfig> f : futureList)
        {
            try {
            	FallbackConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	public Map<String, Set<String>> getBotConfigs() {

        List<Future<BotConfig>> futureList = new ArrayList<Future<BotConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order) so the UI
		// tabs appear in the configured order.
		Map<String, Set<String>> clusterStatus = new LinkedHashMap<String, Set<String>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new BotConfigsCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<BotConfig> f : futureList)
        {
            try {
            	BotConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	/**
	 * Guard rules per edge, in evaluation order, with hit counts.
	 *
	 * Read-only: rules are edited in FRONTCACHE_HOME/conf/guard-rules.conf and applied with the
	 * reload-guard-rules action - same story as the other config screens.
	 */
	public Map<String, List<GuardRuleEntry>> getGuardRules() {

        List<Future<GuardRulesConfig>> futureList = new ArrayList<Future<GuardRulesConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		Map<String, List<GuardRuleEntry>> clusterStatus = new LinkedHashMap<String, List<GuardRuleEntry>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new GuardRulesCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<GuardRulesConfig> f : futureList)
        {
            try {
            	GuardRulesConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result && null != result.getConfig())
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some guard rules may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	public Map<String, Set<String>> getDynamicURLsConfigs() {

        List<Future<DynamicURLsConfig>> futureList = new ArrayList<Future<DynamicURLsConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order) so the UI
		// tabs appear in the configured order.
		Map<String, Set<String>> clusterStatus = new LinkedHashMap<String, Set<String>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new DynamicURLsConfigsCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<DynamicURLsConfig> f : futureList)
        {
            try {
            	DynamicURLsConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	public Map<String, String> getGuardConfigs() {

        List<Future<GuardConfig>> futureList = new ArrayList<Future<GuardConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order) so the UI
		// tabs appear in the configured order.
		Map<String, String> clusterStatus = new LinkedHashMap<String, String>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new GuardConfigsCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<GuardConfig> f : futureList)
        {
            try {
            	GuardConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	public Map<String, String> getResilienceConfigs() {

        List<Future<ResilienceConfig>> futureList = new ArrayList<Future<ResilienceConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order) so the UI
		// tabs appear in the configured order.
		Map<String, String> clusterStatus = new LinkedHashMap<String, String>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new ResilienceConfigsCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<ResilienceConfig> f : futureList)
        {
            try {
            	ResilienceConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	/**
	 *
	 * @param domain
	 * @return
	 */
	public String getHystrixMonitorURLList()
	{
		// [{"name":"My Super App","stream":"http://sg.example.com/hystrix.stream","auth":"","delay":"2000"},{"name":"My Super App","stream":"http://or.example.com/hystrix.stream","auth":"","delay":"2000"}]

		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		List<HystrixConnection> hystrixConnections = new ArrayList<HystrixConnection>();

		for (FrontCacheClient fcClient : fcClients)
		{
			String fcURL = fcClient.getFrontCacheURL();
			if (!fcURL.endsWith("/"))
				fcURL += "/";
			// Deliberately still the pre-2.7 path, not /fc-dashboard.stream: the console is deployed
			// separately from the nodes it monitors, so it has to work against a mixed fleet, and only
			// the old path exists on a 2.6 node. 2.7+ serves both. Switch this when the oldest supported
			// node is 2.7 - see docs/hystrix-to-resilience4j-proposal.md section 8.2.
			hystrixConnections.add(new HystrixConnection(fcClient.getName(), fcURL + "hystrix.stream"));
		}

		String urlListStr = "";
		try {
			urlListStr = jsonMapper.writeValueAsString(hystrixConnections);
		} catch (JsonProcessingException e1) {
			logger.error("Can't serialize array of Hystrix connections", e1);
			urlListStr = e1.getMessage();
			e1.printStackTrace();
		}
		return urlListStr;
	}

	public Set<String> getFrontCacheAgentURLs()
	{
		Set<String> urls = new LinkedHashSet<String>();
		urls.addAll(frontcacheAgentURLs);

		return urls;
	}

	public WebResponse getFromCache(String edgeURL, String key)
	{
		if (null == key)
			return null;

		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
		WebResponse webResponse = fcClient.getFromCache(key.trim());

		return webResponse;
	}


	private List<FrontCacheClient> getFrontCacheAgents()
	{
		return getFrontCacheAgents(false);
	}

	private List<FrontCacheClient> getFrontCacheAgents(boolean activeOnly)
	{
		List<FrontCacheClient> fcClients = new ArrayList<FrontCacheClient>();
		for (String frontcacheURL : frontcacheAgentURLs)
		{
			FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL, siteKey);

			if (activeOnly)
			{
				if (null != fcClient.getCacheState()) // if get state work -> FC is active
					fcClients.add(fcClient);
			} else {
				fcClients.add(fcClient);
			}
		}

		return fcClients;
	}

	public int getEdgesAmount() {
		return frontcacheAgentURLs.size();
	}

	public void addEdge(String edge) {
		if (null != edge)
			frontcacheAgentURLs.add(edge);
	}

	public void removeEdge(String edge) {
		if (null != edge)
			frontcacheAgentURLs.remove(edge);
	}

	public void invalidateEdge(String edgeURL, String filter) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
		fcClient.removeFromCache(filter);
	}

	/**
	 * Triggers purge of expired entries on the given edge.
	 *
	 * @param edgeURL
	 */
	public void purgeEdge(String edgeURL) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
		fcClient.purge();
	}

	/**
	 * Triggers FrontcacheAction.DUMP_KEYS on the given edge - cached keys are
	 * dumped to a file in the edge's ./warmer dir.
	 *
	 * @param edgeURL
	 * @return status message returned by the edge, or an error message if the edge is not available
	 */
	public String dumpKeys(String edgeURL) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
		DumpKeysActionResponse response = fcClient.dumpKeys();
		if (null == response)
			return "ERROR - edge " + edgeURL + " is not available";

		return response.getAction();
	}

	/**
	 * Triggers a backup of the on-disk (L2) index on the given edge. The backup is stored
	 * in a sibling directory of the index dir at the edge (l2-lucene-index.backup-{yyyyMMdd_HHmmss}).
	 *
	 * @param edgeURL
	 * @return status message returned by the edge, or an error message if the edge is not available
	 */
	public String backupEdge(String edgeURL) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
		BackupActionResponse response = fcClient.backup();
		if (null == response)
			return "ERROR - edge " + edgeURL + " is not available";

		return response.getAction();
	}

	// --- Cache Replication ---

	private ExecutorService replicationExecutor = Executors.newSingleThreadExecutor();
	private volatile CacheReplicator activeReplicator = null;

	/**
	 * Starts a cache replication from source edge to target edge.
	 * Runs in a background thread. Only one replication can be active at a time.
	 *
	 * @param sourceUrl the source edge URL
	 * @param targetUrl the target edge URL
	 * @return the initial ReplicationResult (status RUNNING)
	 * @throws IllegalStateException if a replication is already in progress
	 */
	public ReplicationResult startReplication(String sourceUrl, String targetUrl) {
		if (activeReplicator != null && activeReplicator.isRunning()) {
			throw new IllegalStateException("Replication already in progress from "
					+ activeReplicator.getCurrentResult().getSourceUrl()
					+ " to " + activeReplicator.getCurrentResult().getTargetUrl());
		}

		FrontCacheClient source = new FrontCacheClient(sourceUrl, siteKey);
		FrontCacheClient target = new FrontCacheClient(targetUrl, siteKey);

		CacheReplicator replicator = new CacheReplicator(source, target);
		activeReplicator = replicator;

		replicationExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					replicator.run(new ReplicationProgressListener() {
						@Override
						public void onProgress(long transferred, long skipped, long errors, long totalRemaining) {
							logger.debug("Replication progress: transferred={}, skipped={}, errors={}, remaining={}",
									transferred, skipped, errors, totalRemaining);
						}

						@Override
						public void onComplete(ReplicationResult result) {
							logger.info("Replication completed: {}", result);
						}

						@Override
						public void onError(String key, Exception e) {
							logger.warn("Replication error for key {}: {}", key, e.getMessage());
						}
					});
				} finally {
					activeReplicator = null;
				}
			}
		});

		return replicator.getCurrentResult();
	}

	/**
	 * Returns the current replication status, or null if no replication is active.
	 */
	public ReplicationResult getReplicationStatus() {
		CacheReplicator r = activeReplicator;
		return r != null ? r.getCurrentResult() : null;
	}

	/**
	 * Requests cancellation of the running replication.
	 * No-op if no replication is active.
	 */
	public void cancelReplication() {
		CacheReplicator r = activeReplicator;
		if (r != null) {
			r.cancel();
		}
	}

}
