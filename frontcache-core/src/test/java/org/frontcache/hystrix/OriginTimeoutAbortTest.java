/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.pool.PoolStats;
import org.frontcache.FCConfig;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.junit.AfterClass;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The connection-leak regression test, and the reason it exists twice over.
 *
 * A command timeout only interrupts; it cannot unblock a socket read. Without
 * {@code FC_ThroughCache_HttpClient.getFallback()}'s {@code pendingRequest.abort()}, an abandoned origin
 * call keeps its pooled connection until the socket timeout expires - another full 10s - which doubles
 * connection occupancy exactly when the origin is slow and the pool is already tight. That is what
 * happened in the August 2026 outage (docs/archive/2026.08.12-fixed-leaks.md), and the abort is the fix
 * that has to survive the library swap verbatim.
 *
 * It is also this suite's stand-in for a load test. Proposal section 14.4 records that no slow-origin
 * environment exists, so section 10's substitute gate for PR 4 is a deterministic assertion instead: a
 * real {@link PoolingHttpClientConnectionManager} against a real socket that accepts and never answers,
 * asserting the lease comes back. That is a stronger check than watching a graph - it fails on the exact
 * condition rather than on a trend - though it deliberately does not claim to cover sustained slowdown at
 * production concurrency, which stays unverified.
 */
public class OriginTimeoutAbortTest {

	private static final String COMMAND_KEY = "Cache-Origin-http";
	private static final int TIMEOUT_MS = 400;

	/** A socket that accepts connections and never writes a byte back: the slowest possible origin. */
	private static ServerSocket blackHole;
	private static Thread accepter;
	private static final List<Socket> accepted = new CopyOnWriteArrayList<Socket>();

	@BeforeClass
	public static void setUpClass() throws Exception
	{
		URL confMarker = OriginTimeoutAbortTest.class.getClassLoader()
				.getResource("fallback-home/conf/fallbacks.conf");
		assertNotNull("fixture fallback-home/conf/fallbacks.conf not found on test classpath", confMarker);
		File fallbackHome = new File(confMarker.toURI()).getParentFile().getParentFile();
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, fallbackHome.getAbsolutePath());

		FallbackResolverFactory.init(mock(HttpClient.class));

		blackHole = new ServerSocket(0);
		accepter = new Thread(new Runnable() {
			@Override
			public void run()
			{
				while (!blackHole.isClosed())
				{
					try {
						// Hold the socket open and never respond. Closing it would let HttpClient fail
						// fast, which is the opposite of the scenario under test.
						accepted.add(blackHole.accept());
					} catch (Exception e) {
						return;
					}
				}
			}
		}, "black-hole-origin");
		accepter.setDaemon(true);
		accepter.start();
	}

	@AfterClass
	public static void tearDownClass() throws Exception
	{
		for (Socket socket : accepted)
			try { socket.close(); } catch (Exception ignored) { }

		if (null != blackHole)
			blackHole.close();
	}

	@After
	public void tearDown()
	{
		FcResilienceRegistry.reset();
		FcCommandMetrics.reset();
		FcThreadPools.shutdownAll();
		FcResilienceConfig.reset();
	}

	private static void configureTimeout()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command." + COMMAND_KEY + ".execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.command." + COMMAND_KEY + ".execution.isolation.thread.timeoutInMilliseconds",
				String.valueOf(TIMEOUT_MS));
		props.setProperty("hystrix.command." + COMMAND_KEY + ".execution.isolation.thread.interruptOnTimeout", "true");
		props.setProperty("hystrix.threadpool.OriginHitsPool.coreSize", "2");
		FcResilienceConfig.install(props);
	}

	private static RequestContext newContext(String url)
	{
		RequestContext ctx = new RequestContext();
		ctx.setDomain("example.com"); // the command group key
		ctx.setCurrentRequestURL(url);

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURL()).thenReturn(new StringBuffer(url));
		when(request.getMethod()).thenReturn("GET");
		when(request.getParameterMap()).thenReturn(Collections.<String, String[]>emptyMap());
		ctx.setRequest(request);

		return ctx;
	}

	/**
	 * The whole point: after a timeout against an origin that never answers, the pooled connection must
	 * come back. A leaked lease here is the outage.
	 */
	@Test
	public void timeoutReleasesThePooledConnection() throws Exception
	{
		configureTimeout();

		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(4);
		connectionManager.setDefaultMaxPerRoute(4);

		HttpClient client = HttpClients.custom()
				.setConnectionManager(connectionManager)
				// Deliberately far longer than the command timeout: without the abort, the connection
				// would be held for this long after the command has already given up. That gap IS the
				// leak, so the test has to leave room for it to show.
				.setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
						.setSocketTimeout(30000)
						.setConnectTimeout(2000)
						.build())
				.build();

		String url = "http://127.0.0.1:" + blackHole.getLocalPort() + "/slow.html";
		Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();

		FC_ThroughCache_HttpClient command =
				new FC_ThroughCache_HttpClient(url, requestHeaders, client, newContext(url));

		long startedAt = System.currentTimeMillis();
		WebResponse response = command.execute();
		long elapsed = System.currentTimeMillis() - startedAt;

		assertTrue("the command must time out, not wait for the socket timeout (" + elapsed + "ms)",
				elapsed < 5000);
		assertTrue("expected a TIMEOUT, got " + command.getExecutionEvents(),
				command.getExecutionEvents().contains(FcEventType.TIMEOUT));
		assertNotNull("the timeout must resolve a fallback", response);

		// The abort happens on the calling thread; the pool thread is still inside execute() and
		// releases the lease as it unwinds, so poll rather than assert instantly.
		PoolStats stats = awaitLeasedZero(connectionManager, 10000);

		assertEquals("the pooled connection was not released - this is the August 2026 leak: " + stats,
				0, stats.getLeased());
		assertEquals("and nothing is left queued for a connection: " + stats, 0, stats.getPending());

		connectionManager.close();
	}

	/**
	 * Same scenario, run enough times to exhaust a small pool if a single lease leaks. One leaked
	 * connection per slow request is what turns a slow origin into an outage, and a single-shot assertion
	 * would pass even if the release were racy.
	 */
	@Test
	public void repeatedTimeoutsDoNotExhaustThePool() throws Exception
	{
		configureTimeout();

		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(2);
		connectionManager.setDefaultMaxPerRoute(2);

		HttpClient client = HttpClients.custom()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
						.setSocketTimeout(30000)
						.setConnectTimeout(2000)
						// Fail fast rather than block forever once the pool is exhausted, so an actual
						// leak shows up as a failed assertion instead of a hung build.
						.setConnectionRequestTimeout(3000)
						.build())
				.build();

		String url = "http://127.0.0.1:" + blackHole.getLocalPort() + "/slow.html";
		Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();

		for (int attempt = 1; attempt <= 5; attempt++)
		{
			FC_ThroughCache_HttpClient command =
					new FC_ThroughCache_HttpClient(url, requestHeaders, client, newContext(url));

			assertNotNull("attempt " + attempt + " got no fallback", command.execute());
			assertTrue("attempt " + attempt + " did not time out - the pool is probably already exhausted,"
					+ " which is the leak this test is looking for: " + connectionManager.getTotalStats(),
					command.getExecutionEvents().contains(FcEventType.TIMEOUT));

			PoolStats stats = awaitLeasedZero(connectionManager, 10000);
			assertEquals("attempt " + attempt + " leaked a lease: " + stats, 0, stats.getLeased());
		}

		connectionManager.close();
	}

	private static PoolStats awaitLeasedZero(PoolingHttpClientConnectionManager connectionManager, long timeoutMs)
			throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		PoolStats stats = connectionManager.getTotalStats();

		while (System.currentTimeMillis() < deadline && 0 != stats.getLeased())
		{
			Thread.sleep(50);
			stats = connectionManager.getTotalStats();
		}

		return stats;
	}
}
