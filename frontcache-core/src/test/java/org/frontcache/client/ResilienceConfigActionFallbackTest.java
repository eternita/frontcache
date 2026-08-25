/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The 2.7 client asks for {@code get-resilience-configs}; a 2.6 node has never heard of it. That is
 * not an error on the wire - the management servlet answers an unknown action with a PING response, so
 * without a retry the console would render an empty config panel with nothing in any log to say why.
 *
 * This drives {@link FrontCacheClient} against a stub that answers like each node version in turn, and
 * records the actions it was asked for. A stub rather than the e2e suite because the interesting node
 * is the OLD one, and the e2e suite can only run the current code.
 */
public class ResilienceConfigActionFallbackTest {

	private StubNode node;

	@Before
	public void startStub() throws IOException
	{
		node = new StubNode();
		node.start();
	}

	@After
	public void stopStub()
	{
		node.stop();
	}

	/** A 2.7 node: answers the current action, so there must be exactly one request. */
	@Test
	public void currentNodeIsAskedOnce() throws Exception
	{
		node.answerWith("{\"responseStatus\":\"OK\",\"resilienceConfig\":\"resilience.command.default.x=1\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-site-key");

		assertEquals("resilience.command.default.x=1", client.getResilienceConfig());
		assertEquals(Collections.singletonList("get-resilience-configs"), node.actionsSeen());
	}

	/**
	 * A 2.6 node: a ping response to the current action, then the real answer under the legacy field
	 * name. Both the retry and the legacy field have to work for the value to come back.
	 */
	@Test
	public void legacyNodeIsRetriedWithTheOldActionName() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"responseStatus\":\"OK\",\"hystrixConfig\":\"hystrix.command.default.x=1\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-site-key");

		assertEquals("hystrix.command.default.x=1", client.getResilienceConfig());
		assertEquals("the current action first, then the pre-2.7 one",
				java.util.Arrays.asList("get-resilience-configs", "get-hystrix-configs"), node.actionsSeen());
	}

	/**
	 * A node that recognizes the action but cannot read the file answers with an ERROR, not a ping.
	 * Retrying that would be a wasted round trip against a node that already gave its answer.
	 */
	@Test
	public void aRealErrorIsNotRetried() throws Exception
	{
		node.answerWith("{\"responseStatus\":\"ERROR\",\"errorDescription\":\"resilience.properties not found\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-site-key");

		assertNull(client.getResilienceConfig());
		assertEquals(Collections.singletonList("get-resilience-configs"), node.actionsSeen());
	}

	/** The deprecated accessor must route through the same fallback, not the raw legacy action. */
	@Test
	public void theDeprecatedAccessorGetsTheSameTreatment() throws Exception
	{
		node.answerWith("{\"responseStatus\":\"OK\",\"resilienceConfig\":\"resilience.command.default.x=1\"}");

		@SuppressWarnings("deprecation")
		String viaLegacyAccessor = new FrontCacheClient(node.url(), "test-site-key").getHystrixConfig();

		assertEquals("resilience.command.default.x=1", viaLegacyAccessor);
		assertEquals(Collections.singletonList("get-resilience-configs"), node.actionsSeen());
	}

	// ---- stub node ----------------------------------------------------------------------------

	/**
	 * A single-threaded HTTP/1.1 stub that hands out one canned body per request, in order, and records
	 * the {@code action} form parameter of each. Deliberately tiny: it exists to observe which actions
	 * the client sends, which no assertion against a real node can show.
	 */
	private static final class StubNode {

		private final ServerSocket serverSocket;
		private final List<String> actions = Collections.synchronizedList(new ArrayList<String>());
		private final CountDownLatch stopped = new CountDownLatch(1);

		private volatile String[] bodies = new String[0];
		private volatile boolean running = true;
		private Thread thread;

		StubNode() throws IOException
		{
			serverSocket = new ServerSocket(0);
		}

		void answerWith(String... responseBodies)
		{
			bodies = responseBodies;
		}

		String url()
		{
			return "http://127.0.0.1:" + serverSocket.getLocalPort();
		}

		List<String> actionsSeen()
		{
			return new ArrayList<String>(actions);
		}

		void start()
		{
			thread = new Thread(new Runnable() {
				@Override
				public void run() {
					int served = 0;
					try {
						while (running)
						{
							Socket socket = serverSocket.accept();
							try {
								readRequestAndRecordAction(socket);
								String responseBody = (served < bodies.length) ? bodies[served] : "{}";
								served++;
								write(socket, responseBody);
							} finally {
								socket.close();
							}
						}
					} catch (IOException closed) {
						// the socket was closed by stop() - that is how this loop ends
					} finally {
						stopped.countDown();
					}
				}
			}, "stub-node");
			thread.setDaemon(true);
			thread.start();
		}

		private void readRequestAndRecordAction(Socket socket) throws IOException
		{
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

			int contentLength = 0;
			String line;
			while (null != (line = reader.readLine()) && 0 != line.length())
				if (line.toLowerCase().startsWith("content-length:"))
					contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());

			char[] body = new char[contentLength];
			int read = 0;
			while (read < contentLength)
			{
				int n = reader.read(body, read, contentLength - read);
				if (-1 == n)
					break;
				read += n;
			}

			for (String pair : new String(body, 0, read).split("&"))
				if (pair.startsWith("action="))
					actions.add(pair.substring("action=".length()));
		}

		private void write(Socket socket, String body) throws IOException
		{
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			OutputStream out = socket.getOutputStream();
			out.write(("HTTP/1.1 200 OK\r\n"
					+ "Content-Type: application/json\r\n"
					+ "Content-Length: " + bytes.length + "\r\n"
					+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(bytes);
			out.flush();
		}

		void stop()
		{
			running = false;
			try {
				serverSocket.close();
			} catch (IOException ignored) { }

			try {
				stopped.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

}
