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
 * {@code Input-Requests} must count client traffic, not the node's own includes.
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
public class IncludeRequestsCommandKeyTest {

	/** The shipped file, minus any Include-Requests stanza - i.e. every already-deployed node. */
	private static Properties propertiesWithoutIncludeStanza()
	{
		Properties props = new Properties();
		props.setProperty("resilience.command.default.circuitBreaker.errorThresholdPercentage", "50");
		props.setProperty("resilience.command.Input-Requests.execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("resilience.command.Input-Requests.execution.isolation.thread.timeoutInMilliseconds", "20000");
		props.setProperty("resilience.command.Input-Requests.execution.isolation.semaphore.maxConcurrentRequests", "500");
		return props;
	}

	private static RequestContext contextOfType(String requestType)
	{
		RequestContext context = new RequestContext();
		context.setRequestType(requestType);
		return context;
	}

	@Test
	public void aClientRequestStaysOnInputRequests()
	{
		assertEquals(FC_Total.COMMAND_KEY_INPUT_REQUESTS,
				FC_Total.commandKeyFor(contextOfType(FCHeaders.COMPONENT_TOPLEVEL)));
	}

	/** The regression: an include re-entering the front door must not be counted as client traffic. */
	@Test
	public void anIncludeComingBackThroughTheFrontDoorIsCountedSeparately()
	{
		assertEquals(FC_Total.COMMAND_KEY_INCLUDE_REQUESTS,
				FC_Total.commandKeyFor(contextOfType(FCHeaders.COMPONENT_INCLUDE)));

		assertEquals("an async include is still an include",
				FC_Total.COMMAND_KEY_INCLUDE_REQUESTS,
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
		assertEquals(FC_Total.COMMAND_KEY_INPUT_REQUESTS, FC_Total.commandKeyFor(contextOfType(null)));
		assertEquals(FC_Total.COMMAND_KEY_INPUT_REQUESTS, FC_Total.commandKeyFor(contextOfType("something-else")));
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

		FcCommandConfig includes = config.forCommand(FC_Total.COMMAND_KEY_INCLUDE_REQUESTS, null);

		assertEquals("must not fall back to the built-in THREAD", Isolation.SEMAPHORE, includes.getIsolation());
		assertEquals("must not fall back to the built-in 1000ms", 20000, includes.getTimeoutMs());
		assertEquals("must not fall back to the built-in 10 permits", 500, includes.getSemaphoreMaxConcurrentRequests());
	}

	/** Inheritance is per property: a partial stanza overrides only what it sets. */
	@Test
	public void anExplicitIncludeStanzaOverridesInheritancePerProperty()
	{
		Properties props = propertiesWithoutIncludeStanza();
		props.setProperty("resilience.command.Include-Requests.execution.isolation.semaphore.maxConcurrentRequests", "25");

		FcCommandConfig includes = new FcResilienceConfig(props)
				.forCommand(FC_Total.COMMAND_KEY_INCLUDE_REQUESTS, null);

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
				.forCommand(FC_Total.COMMAND_KEY_INCLUDE_REQUESTS, null);

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
		assertEquals("Input-Requests keeps its own value", 20000,
				config.forCommand(FC_Total.COMMAND_KEY_INPUT_REQUESTS, null).getTimeoutMs());
	}
}
