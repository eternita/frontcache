/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.frontcache.resilience.FcCommandConfig;
import org.frontcache.resilience.FcCommandMetrics;
import org.frontcache.resilience.FcEventType;
import org.frontcache.resilience.FcResilienceConfig;
import org.frontcache.resilience.FcResilienceRegistry;
import org.frontcache.resilience.FcThreadPools;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Serializes {@link FcCommandMetrics} and {@link FcThreadPools} into the Hystrix dashboard's JSON,
 * replacing {@code SerialHystrixDashboardData}.
 *
 * This is the compatibility contract of the whole migration, and the reason it is hand-written rather
 * than adapted from a metrics library: the consumer is vendored JavaScript whose {@code validateData()}
 * throws on the first missing field, blanking the panel with a console error and no server-side
 * symptom. The field names, their types and the percentile map's shape are frozen by
 * {@code dashboard-json-keys.txt} and asserted by {@code FcDashboardJsonTest} - 48 keys on a command
 * frame and 18 on a thread-pool frame, which is the full set Hystrix emitted, not just the 36 and 16
 * the dashboard happens to assert. An external Turbine or a custom dashboard may read the rest.
 *
 * Written by hand with a StringBuilder rather than through Jackson for one reason: field ORDER is
 * stable and reviewable this way, which makes a diff of an old and a new frame readable during the
 * cut-over. See proposal sections 7.1-7.3.
 */
public class FcDashboardJson {

	private static final String[] PERCENTILE_KEYS = { "0", "25", "50", "75", "90", "95", "99", "99.5", "100" };

	private FcDashboardJson() { }

	/**
	 * One frame per command plus one per thread pool.
	 *
	 * Thread-pool frames are NOT filtered by anything. A pool is a node-level resource shared by every
	 * THREAD-isolated command, so there is no per-domain answer to give - the old code compared the pool
	 * name against the caller's domain ({@code "OriginHitsPool".equals(domain)}), which is false for
	 * every domain that can exist, which is why the console's thread-pool panel has never rendered.
	 * Proposal section 6.6, defect 1.
	 */
	public static List<String> frames()
	{
		List<String> frames = new ArrayList<String>();

		for (FcCommandMetrics metrics : FcCommandMetrics.all())
			frames.add(commandFrame(metrics));

		for (String poolKey : FcThreadPools.all())
		{
			ThreadPoolExecutor pool = FcThreadPools.existing(poolKey);
			if (null != pool)
				frames.add(threadPoolFrame(poolKey, pool));
		}

		return frames;
	}

	public static String commandFrame(FcCommandMetrics metrics)
	{
		FcCommandConfig config = FcResilienceConfig.getInstance().forCommand(metrics.getCommandKey(), null);
		CircuitBreaker breaker = FcResilienceRegistry.existingCircuitBreaker(metrics.getCommandKey());

		StringBuilder json = new StringBuilder(2048);
		json.append('{');

		// The dashboard dispatches on this literal - hystrixCommand.js:76. Renaming it means editing
		// the vendored JS, which is out of scope here.
		field(json, "type", "HystrixCommand");
		field(json, "name", metrics.getCommandKey());
		field(json, "group", metrics.getGroup());
		field(json, "currentTime", System.currentTimeMillis());

		field(json, "isCircuitBreakerOpen", null != breaker && CircuitBreaker.State.OPEN == breaker.getState());

		field(json, "errorPercentage", metrics.getErrorPercentage());
		field(json, "errorCount", metrics.getRollingErrorCount());
		field(json, "requestCount", metrics.getRollingTotalRequests());

		field(json, "rollingCountBadRequests", metrics.getRollingCount(FcEventType.BAD_REQUEST));
		field(json, "rollingCountCollapsedRequests", metrics.getRollingCount(FcEventType.COLLAPSED));
		field(json, "rollingCountEmit", 0);
		field(json, "rollingCountExceptionsThrown", metrics.getRollingCount(FcEventType.EXCEPTION_THROWN));
		field(json, "rollingCountFailure", metrics.getRollingCount(FcEventType.FAILURE));
		field(json, "rollingCountFallbackEmit", 0);
		field(json, "rollingCountFallbackFailure", metrics.getRollingCount(FcEventType.FALLBACK_FAILURE));
		field(json, "rollingCountFallbackMissing", 0);
		// Always 0, and structurally so: the fallback semaphore is parsed and reported but never
		// enforced, because a call only reaches its fallback while holding an execution permit.
		// Proposal section 6.3.
		field(json, "rollingCountFallbackRejection", metrics.getRollingCount(FcEventType.FALLBACK_REJECTION));
		field(json, "rollingCountFallbackSuccess", metrics.getRollingCount(FcEventType.FALLBACK_SUCCESS));
		field(json, "rollingCountResponsesFromCache", metrics.getRollingCount(FcEventType.RESPONSE_FROM_CACHE));
		field(json, "rollingCountSemaphoreRejected", metrics.getRollingCount(FcEventType.SEMAPHORE_REJECTED));
		field(json, "rollingCountShortCircuited", metrics.getRollingCount(FcEventType.SHORT_CIRCUITED));
		field(json, "rollingCountSuccess", metrics.getRollingCount(FcEventType.SUCCESS));
		field(json, "rollingCountThreadPoolRejected", metrics.getRollingCount(FcEventType.THREAD_POOL_REJECTED));
		field(json, "rollingCountTimeout", metrics.getRollingCount(FcEventType.TIMEOUT));

		field(json, "currentConcurrentExecutionCount", metrics.getCurrentConcurrentExecutionCount());
		field(json, "rollingMaxConcurrentExecutionCount", metrics.getRollingMaxConcurrentExecutionCount());

		field(json, "latencyExecute_mean", metrics.getLatencyMean());
		percentiles(json, "latencyExecute", metrics.getLatencyPercentiles());
		// Frontcache has no separate "total" measurement - execute() IS the whole command - so these
		// mirror the execute values rather than reporting a fabricated second number.
		field(json, "latencyTotal_mean", metrics.getLatencyMean());
		percentiles(json, "latencyTotal", metrics.getLatencyPercentiles());

		field(json, "propertyValue_circuitBreakerRequestVolumeThreshold", config.getRequestVolumeThreshold());
		field(json, "propertyValue_circuitBreakerSleepWindowInMilliseconds", config.getSleepWindowMs());
		field(json, "propertyValue_circuitBreakerErrorThresholdPercentage", config.getErrorThresholdPercentage());
		field(json, "propertyValue_circuitBreakerForceOpen", false);
		field(json, "propertyValue_circuitBreakerForceClosed", false);
		field(json, "propertyValue_circuitBreakerEnabled", true);
		field(json, "propertyValue_executionIsolationStrategy", config.getIsolation().name());
		field(json, "propertyValue_executionIsolationThreadTimeoutInMilliseconds", config.getTimeoutMs());
		field(json, "propertyValue_executionTimeoutInMilliseconds", config.getTimeoutMs());
		field(json, "propertyValue_executionIsolationThreadInterruptOnTimeout", config.isInterruptOnTimeout());
		field(json, "propertyValue_executionIsolationThreadPoolKeyOverride", (String) null);
		field(json, "propertyValue_executionIsolationSemaphoreMaxConcurrentRequests",
				config.getSemaphoreMaxConcurrentRequests());
		// Reported, not enforced - see rollingCountFallbackRejection above. The value is the operator's,
		// so an existing hystrix.properties round-trips.
		field(json, "propertyValue_fallbackIsolationSemaphoreMaxConcurrentRequests",
				config.getFallbackSemaphoreMaxConcurrentRequests());
		field(json, "propertyValue_requestCacheEnabled", false);
		field(json, "propertyValue_requestLogEnabled", false);
		// The dashboard DIVIDES by this to compute ratePerSecond, so it has to carry the real window.
		field(json, "propertyValue_metricsRollingStatisticalWindowInMilliseconds", config.getRollingStatsMs());

		field(json, "reportingHosts", 1);
		field(json, "threadPool", config.getThreadPoolKey());

		// Not a Hystrix field. The watchdog's overrun counter (proposal section 6.2) - deliberately its
		// own key rather than folded into rollingCountTimeout, which would move errorPercentage and
		// could open a circuit on a request that succeeded. Extra keys are safe: the dashboard asserts
		// presence and ignores anything it does not know.
		lastField(json, "rollingCountBudgetOverrunObserved",
				metrics.getRollingCount(FcEventType.BUDGET_OVERRUN_OBSERVED));

		json.append('}');
		return json.toString();
	}

	public static String threadPoolFrame(String poolKey, ThreadPoolExecutor pool)
	{
		FcResilienceConfig config = FcResilienceConfig.getInstance();

		StringBuilder json = new StringBuilder(1024);
		json.append('{');

		field(json, "type", "HystrixThreadPool");
		field(json, "name", poolKey);
		field(json, "currentTime", System.currentTimeMillis());

		field(json, "currentActiveCount", pool.getActiveCount());
		field(json, "currentCompletedTaskCount", pool.getCompletedTaskCount());
		field(json, "currentCorePoolSize", pool.getCorePoolSize());
		field(json, "currentLargestPoolSize", pool.getLargestPoolSize());
		field(json, "currentMaximumPoolSize", pool.getMaximumPoolSize());
		field(json, "currentPoolSize", pool.getPoolSize());
		field(json, "currentQueueSize", pool.getQueue().size());
		field(json, "currentTaskCount", pool.getTaskCount());

		// Hystrix reported these as rolling counts; the executor only exposes lifetime totals, and a
		// lifetime total is the honest thing to publish rather than a rolling number we do not measure.
		// The dashboard renders them as counters either way.
		field(json, "rollingCountThreadsExecuted", pool.getCompletedTaskCount());
		field(json, "rollingMaxActiveThreads", pool.getLargestPoolSize());
		field(json, "rollingCountCommandRejections", FcThreadPools.rejectionCount(poolKey));

		field(json, "propertyValue_queueSizeRejectionThreshold", config.poolMaxQueueSize(poolKey));
		field(json, "propertyValue_metricsRollingStatisticalWindowInMilliseconds",
				FcResilienceConfig.DEFAULT_ROLLING_STATS_MS);

		lastField(json, "reportingHosts", 1);

		json.append('}');
		return json.toString();
	}

	// ---- primitives ---------------------------------------------------------

	private static void field(StringBuilder json, String name, String value)
	{
		json.append('"').append(name).append("\":");
		if (null == value)
			json.append("null");
		else
			json.append('"').append(escape(value)).append('"');
		json.append(',');
	}

	private static void field(StringBuilder json, String name, long value)
	{
		json.append('"').append(name).append("\":").append(value).append(',');
	}

	private static void field(StringBuilder json, String name, boolean value)
	{
		json.append('"').append(name).append("\":").append(value).append(',');
	}

	private static void lastField(StringBuilder json, String name, long value)
	{
		json.append('"').append(name).append("\":").append(value);
	}

	private static void percentiles(StringBuilder json, String name, Map<String, Long> values)
	{
		json.append('"').append(name).append("\":{");
		for (int i = 0; i < PERCENTILE_KEYS.length; i++)
		{
			Long value = values.get(PERCENTILE_KEYS[i]);
			json.append('"').append(PERCENTILE_KEYS[i]).append("\":").append(null == value ? 0L : value.longValue());
			if (i < PERCENTILE_KEYS.length - 1)
				json.append(',');
		}
		json.append("},");
	}

	/**
	 * Command keys and pool names are ours, but the group is a configured domain, so it is escaped
	 * rather than trusted - a stray quote would produce JSON the dashboard cannot parse at all.
	 */
	private static String escape(String value)
	{
		StringBuilder escaped = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			switch (c) {
				case '"':  escaped.append("\\\""); break;
				case '\\': escaped.append("\\\\"); break;
				case '\n': escaped.append("\\n");  break;
				case '\r': escaped.append("\\r");  break;
				case '\t': escaped.append("\\t");  break;
				default:
					if (c < 0x20)
						escaped.append(String.format("\\u%04x", Integer.valueOf(c)));
					else
						escaped.append(c);
			}
		}
		return escaped.toString();
	}
}
