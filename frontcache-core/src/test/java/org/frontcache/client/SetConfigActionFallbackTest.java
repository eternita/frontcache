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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.frontcache.io.ActionResponse;
import org.frontcache.io.ConfigFile;
import org.frontcache.io.SetConfigActionResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A console that can save config talking to a node that has never heard of saving config.
 *
 * <b>This is the assertion that matters most in the whole feature.</b> An unrecognized action is not
 * an error on the wire - the management servlet answers it with a PING - so a save to an older node
 * parses cleanly into a response whose {@code responseStatus} is OK. Without the ping check the
 * console would tell an operator their fleet's guard rules were saved and applied when nothing was
 * written anywhere, and a mixed fleet is the normal state: the console upgrades independently of the
 * nodes, in either order.
 *
 * Driven against {@link StubNode} rather than the e2e suite, because the interesting node is the OLD
 * one and the e2e suite can only run the current code.
 */
public class SetConfigActionFallbackTest {

	private StubNode node;

	@Before
	public void startStub() throws java.io.IOException
	{
		node = new StubNode();
		node.start();
	}

	@After
	public void stopStub()
	{
		node.stop();
	}

	/** A current node: one request, and its answer is passed through as it is. */
	@Test
	public void aCurrentNodeAnswerIsPassedThrough() throws Exception
	{
		node.answerWith("{\"action\":\"set-config\",\"responseStatus\":\"OK\",\"file\":\"bots.conf\","
				+ "\"written\":true,\"applied\":true,\"backupFile\":\"bots.conf.backup-20260907_120000\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");
		SetConfigActionResponse response = client.setConfig(ConfigFile.BOTS, "Googlebot\n", null, true);

		assertNotNull(response);
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		assertTrue(response.isWritten());
		assertTrue(response.isApplied());
		assertEquals("bots.conf.backup-20260907_120000", response.getBackupFile());
		assertEquals(Collections.singletonList("set-config"), node.actionsSeen());
	}

	/**
	 * A node that predates config editing. Its ping must become a refusal that names the file and says
	 * what to do instead - never a success, and never a retry: there is no older action to fall back
	 * to, so retrying would only be a second wasted round trip.
	 */
	@Test
	public void aPingBecomesARefusalAndIsNotRetried() throws Exception
	{
		node.answerWith("{\"action\":\"ping\",\"responseStatus\":\"OK\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");
		SetConfigActionResponse response = client.setConfig(ConfigFile.GUARD_RULES, "x | uri~^/ | allow\n", null, true);

		assertNotNull(response);
		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse("a ping must never read as a successful write", response.isWritten());
		assertFalse(response.isApplied());
		assertEquals("guard-rules.conf", response.getFile());
		assertTrue("the operator needs to be told what to do instead, got: " + response.getErrorDescription(),
				response.getErrorDescription().contains("guard-rules.conf"));
		assertEquals(Collections.singletonList("set-config"), node.actionsSeen());
	}

	/** A node that refused the write for its own reasons - that answer is the operator's, unchanged. */
	@Test
	public void aRefusalFromTheNodeIsPassedThrough() throws Exception
	{
		node.answerWith("{\"action\":\"set-config\",\"responseStatus\":\"ERROR\",\"file\":\"bots.conf\","
				+ "\"written\":false,\"errorDescription\":\"config writes are disabled on this node\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");
		SetConfigActionResponse response = client.setConfig(ConfigFile.BOTS, "Googlebot\n", null, true);

		assertNotNull(response);
		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertEquals("config writes are disabled on this node", response.getErrorDescription());
		assertFalse(response.isWritten());
	}

	/**
	 * A field a newer node sends that this client does not know about must not abort the parse -
	 * {@code ActionResponse} is annotated for exactly this, and skew runs in both directions.
	 */
	@Test
	public void anUnknownFieldFromANewerNodeIsIgnored() throws Exception
	{
		node.answerWith("{\"action\":\"set-config\",\"responseStatus\":\"OK\",\"file\":\"bots.conf\","
				+ "\"written\":true,\"applied\":true,\"somethingFromTheFuture\":{\"a\":1}}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");
		SetConfigActionResponse response = client.setConfig(ConfigFile.BOTS, "Googlebot\n", null, true);

		assertNotNull(response);
		assertTrue(response.isWritten());
	}
}
