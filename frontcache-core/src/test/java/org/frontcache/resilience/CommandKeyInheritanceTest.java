/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;

import java.util.Properties;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.resilience.FcCommandConfig.Isolation;
import org.junit.Test;

/**
 * {@code Input-Request} must count client traffic, not the node's own includes.
 *
 * An {@code <fc:include>} that misses cache is fetched over HTTP against the {@code Host} header -
 * back through the CDN and in at this node's own front door - so it arrives as an ordinary request
 * and used to be tallied on the dashboard's headline circuit. On a production edge that read
 * ~1200/s against ~11/s of real client traffic. Fixing the cache keys removed the volume but not
 * the miscounting: a cache-missing include still re-enters, and in filter mode or a chained
 * topology it re-enters however includes are routed.
 *
 * The second half of this is the trap that made the change worth being careful about.
 * {@code FcResilienceConfig} resolves {@code resilience.command.<key>.<prop>}, then {@code default}
 * scope, then a built-in - and the shipped file sets {@code default} only for circuit-breaker
 * properties. So a brand-new command key would land on {@code THREAD}, {@code
 * maxConcurrentRequests=10}, {@code timeout=1000} on every node whose properties file predates it.
 * The command would work and then quietly shed into fallback fragments above 10 concurrent calls.
 * Inheritance is what prevents that, and it only helps if it is actually wired up - hence these
 * tests.
 */
public class CommandKeyInheritanceTest {

	/**
	 * A pre-2.9.0 file: no Include stanza, and the OLD command key spellings - i.e. every node that
	 * has not had its resilience.properties edited since the upgrade. Resolving {@code Include-Request}
	 * against this walks the whole chain, {@code Include-Request -> Input-Request -> Input-Requests},
	 * which is the case a single inheritance hop got wrong.
	 */
	private static Properties propertiesWithoutIncludeStanza()
	{
		Properties props = new Properties();
		props.setProperty("resilience.command.default.circuitBreaker.errorThresholdPercentage", "50");
		props.setProperty("resilience.command.Input-Requests.execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("resilience.command.Input-Requests.execution.isolation.thread.timeoutInMilliseconds", "20000");
		props.setProperty("resilience.command.Input-Requests.execution.isolation.semaphore.maxConcurrentRequests", "500");
		return props;
	}

	/** The same file with the current spellings - a node whose config HAS been updated. */
	private static Properties currentProperties()
	{
		Properties props = new Properties();
		props.setProperty("resilience.command.default.circuitBreaker.errorThresholdPercentage", "50");
		props.setProperty("resilience.command.Input-Request.execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("resilience.command.Input-Request.execution.isolation.thread.timeoutInMilliseconds", "20000");
		props.setProperty("resilience.command.Input-Request.execution.isolation.semaphore.maxConcurrentRequests", "500");
		return props;
	}

	private static RequestContext contextOfType(String requestType)
	{
		RequestContext context = new RequestContext();
		context.setRequestType(requestType);
		return context;
	}

	@Test
	public void aClientRequestStaysOnInputRequest()
	{
		assertEquals(FC_Total.COMMAND_KEY_INPUT_REQUEST,
				FC_Total.commandKeyFor(contextOfType(FCHeaders.COMPONENT_TOPLEVEL)));
	}

	/** The regression: an include re-entering the front door must not be counted as client traffic. */
	@Test
	public void anIncludeComingBackThroughTheFrontDoorIsCountedSeparately()
	{
		assertEquals(FC_Total.COMMAND_KEY_INCLUDE_REQUEST,
				FC_Total.commandKeyFor(contextOfType(FCHeaders.COMPONENT_INCLUDE)));

		assertEquals("an async include is still an include",
				FC_Total.COMMAND_KEY_INCLUDE_REQUEST,
				FC_Total.commandKeyFor(contextOfType(FCHeaders.COMPONENT_ASYNC_INCLUDE)));
	}

	/**
	 * A context that never went through {@code init()} cannot happen for a real request, but the
	 * conservative answer matters: an unknown request stays visible in the headline number instead of
	 * being filed away as an include.
	 */
	@Test
	public void anUnclassifiedRequestCountsAsClientTraffic()
	{
		assertEquals(FC_Total.COMMAND_KEY_INPUT_REQUEST, FC_Total.commandKeyFor(contextOfType(null)));
		assertEquals(FC_Total.COMMAND_KEY_INPUT_REQUEST, FC_Total.commandKeyFor(contextOfType("something-else")));
	}

	/**
	 * The load-bearing one. On an un-updated properties file the new key must pick up Input-Requests'
	 * tuning, NOT the built-in defaults - otherwise the split silently caps includes at 10 concurrent
	 * with a 1-second timeout.
	 */
	@Test
	public void includeRequestsInheritsInputRequestsWhenTheFileSaysNothing()
	{
		FcResilienceConfig config = new FcResilienceConfig(propertiesWithoutIncludeStanza());

		FcCommandConfig includes = config.forCommand(FC_Total.COMMAND_KEY_INCLUDE_REQUEST, null);

		assertEquals("must not fall back to the built-in THREAD", Isolation.SEMAPHORE, includes.getIsolation());
		assertEquals("must not fall back to the built-in 1000ms", 20000, includes.getTimeoutMs());
		assertEquals("must not fall back to the built-in 10 permits", 500, includes.getSemaphoreMaxConcurrentRequests());
	}

	/** Inheritance is per property: a partial stanza overrides only what it sets. */
	@Test
	public void anExplicitIncludeStanzaOverridesInheritancePerProperty()
	{
		Properties props = propertiesWithoutIncludeStanza();
		props.setProperty("resilience.command.Include-Request.execution.isolation.semaphore.maxConcurrentRequests", "25");

		FcCommandConfig includes = new FcResilienceConfig(props)
				.forCommand(FC_Total.COMMAND_KEY_INCLUDE_REQUEST, null);

		assertEquals("the explicit value must win", 25, includes.getSemaphoreMaxConcurrentRequests());
		assertEquals("what it did not set must still be inherited", 20000, includes.getTimeoutMs());
		assertEquals(Isolation.SEMAPHORE, includes.getIsolation());
	}

	/** The legacy hystrix.* prefix is inherited too - it is the same stanza under an older name. */
	@Test
	public void inheritanceAlsoSeesTheLegacyHystrixPrefix()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command.Input-Requests.execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("hystrix.command.Input-Requests.execution.isolation.thread.timeoutInMilliseconds", "17000");

		FcCommandConfig includes = new FcResilienceConfig(props)
				.forCommand(FC_Total.COMMAND_KEY_INCLUDE_REQUEST, null);

		assertEquals(Isolation.SEMAPHORE, includes.getIsolation());
		assertEquals(17000, includes.getTimeoutMs());
	}

	/**
	 * Inheritance must not shadow an explicit {@code default} stanza for OTHER keys, and must not
	 * make Input-Requests itself inherit from anything.
	 */
	@Test
	public void otherKeysAreUnaffected()
	{
		Properties props = propertiesWithoutIncludeStanza();
		props.setProperty("resilience.command.default.execution.isolation.thread.timeoutInMilliseconds", "3000");

		FcResilienceConfig config = new FcResilienceConfig(props);

		assertEquals("an unrelated key still takes default scope", 3000,
				config.forCommand("Cache-Hits", null).getTimeoutMs());
		assertEquals("Input-Request keeps its own value", 20000,
				config.forCommand(FC_Total.COMMAND_KEY_INPUT_REQUEST, null).getTimeoutMs());
	}

	// ---- the 2.9.0 command-key renames ------------------------------------------------------------

	/**
	 * The whole point of {@link FcCommandKeys#LEGACY_COMMAND_KEYS}. Renaming a command key resets that
	 * command to the built-in defaults on every node whose resilience.properties still says the old
	 * name - and for an origin call tuned to ten seconds, landing on {@code timeout=1000} is not a
	 * metrics change, it is an outage under load. Each new key falls back on its old name instead.
	 */
	@Test
	public void everyRenamedKeyInheritsFromItsOldName()
	{
		Properties props = new Properties();
		props.setProperty("resilience.command.Input-Requests.execution.isolation.thread.timeoutInMilliseconds", "20000");
		props.setProperty("resilience.command.Origin-Hits.execution.isolation.thread.timeoutInMilliseconds", "21000");
		props.setProperty("resilience.command.Cache-Origin-http.execution.isolation.thread.timeoutInMilliseconds", "22000");

		FcResilienceConfig config = new FcResilienceConfig(props);

		assertEquals(20000, config.forCommand(FcCommandKeys.INPUT_REQUEST, null).getTimeoutMs());
		assertEquals(21000, config.forCommand(FcCommandKeys.CACHE_BYPASS, null).getTimeoutMs());
		assertEquals(22000, config.forCommand(FcCommandKeys.CACHE_MISS, null).getTimeoutMs());
	}

	/**
	 * The chain, which is what a single inheritance hop got wrong. An un-edited file has neither an
	 * Include stanza nor the new spelling of anything, so {@code Include-Request} only reaches a real
	 * value by going through {@code Input-Request} to {@code Input-Requests}.
	 */
	@Test
	public void includeRequestResolvesThroughTwoHopsOnAPre290File()
	{
		FcCommandConfig includes = new FcResilienceConfig(propertiesWithoutIncludeStanza())
				.forCommand(FcCommandKeys.INCLUDE_REQUEST, null);

		assertEquals("must not fall back to the built-in 1000ms", 20000, includes.getTimeoutMs());
		assertEquals("must not fall back to the built-in 10 permits", 500,
				includes.getSemaphoreMaxConcurrentRequests());
		assertEquals(Isolation.SEMAPHORE, includes.getIsolation());
	}

	/** And on an updated file the chain stops at the first hop, with the same answer. */
	@Test
	public void includeRequestResolvesInOneHopOnACurrentFile()
	{
		FcCommandConfig includes = new FcResilienceConfig(currentProperties())
				.forCommand(FcCommandKeys.INCLUDE_REQUEST, null);

		assertEquals(20000, includes.getTimeoutMs());
		assertEquals(500, includes.getSemaphoreMaxConcurrentRequests());
		assertEquals(Isolation.SEMAPHORE, includes.getIsolation());
	}

	/** The new name wins when a half-migrated file carries both. */
	@Test
	public void theCurrentNameBeatsTheOldOne()
	{
		Properties props = new Properties();
		props.setProperty("resilience.command.Cache-Origin-http.execution.isolation.thread.timeoutInMilliseconds", "22000");
		props.setProperty("resilience.command.Cache-Miss.execution.isolation.thread.timeoutInMilliseconds", "9000");

		assertEquals(9000, new FcResilienceConfig(props)
				.forCommand(FcCommandKeys.CACHE_MISS, null).getTimeoutMs());
	}

	/**
	 * The thread pool key was renamed too, and it fails harder than a command key when the fallback is
	 * missing: coreSize drops to threadpool.default, or to the built-in 10 on a file that never set a
	 * default, capping every origin call on the node at ten threads.
	 */
	@Test
	public void theRenamedThreadPoolKeyInheritsFromItsOldName()
	{
		Properties props = new Properties();
		props.setProperty("resilience.threadpool.OriginHitsPool.coreSize", "200");
		props.setProperty("resilience.threadpool.OriginHitsPool.maxQueueSize", "7");

		FcResilienceConfig config = new FcResilienceConfig(props);

		assertEquals("must not fall back to the built-in 10", 200,
				config.poolCoreSize(FcCommandKeys.ORIGIN_POOL));
		assertEquals(7, config.poolMaxQueueSize(FcCommandKeys.ORIGIN_POOL));
	}

	/** The legacy hystrix.threadpool.* prefix is inherited too - same stanza, older prefix. */
	@Test
	public void theRenamedThreadPoolKeyAlsoSeesTheLegacyPrefix()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.threadpool.OriginHitsPool.coreSize", "150");

		assertEquals(150, new FcResilienceConfig(props).poolCoreSize(FcCommandKeys.ORIGIN_POOL));
	}

	/** The new pool name wins when a half-migrated file carries both. */
	@Test
	public void theCurrentThreadPoolNameBeatsTheOldOne()
	{
		Properties props = new Properties();
		props.setProperty("resilience.threadpool.OriginHitsPool.coreSize", "200");
		props.setProperty("resilience.threadpool.OriginPool.coreSize", "80");

		assertEquals(80, new FcResilienceConfig(props).poolCoreSize(FcCommandKeys.ORIGIN_POOL));
	}

	/**
	 * An un-renamed pool key must NOT pick the fallback up - only OriginPool inherits, and a
	 * differently-named pool still takes default scope like it always did.
	 */
	@Test
	public void otherPoolsAreUnaffected()
	{
		Properties props = new Properties();
		props.setProperty("resilience.threadpool.OriginHitsPool.coreSize", "200");
		props.setProperty("resilience.threadpool.default.coreSize", "30");

		assertEquals(30, new FcResilienceConfig(props).poolCoreSize("SomeOtherPool"));
	}

	/**
	 * {@code Cache-Miss} is two old keys combined, and it can only inherit from one of them. It takes
	 * {@code Cache-Origin-http}, the key every deployment mode used - a filter-mode node fetches its
	 * includes over HTTP. A node that tuned the two differently has to state {@code Cache-Miss}
	 * explicitly; this pins which one wins so that is a documented answer rather than map order.
	 */
	@Test
	public void cacheMissInheritsFromTheHttpKeyNotTheFilterOne()
	{
		Properties props = new Properties();
		props.setProperty("resilience.command.Cache-Origin-http.execution.isolation.thread.timeoutInMilliseconds", "22000");
		props.setProperty("resilience.command.Cache-Origin-filter.execution.isolation.thread.timeoutInMilliseconds", "33000");

		assertEquals(22000, new FcResilienceConfig(props)
				.forCommand(FcCommandKeys.CACHE_MISS, null).getTimeoutMs());
	}
}
