/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include.combine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The memo that stops an origin without a combine handler from costing one wasted request per group
 * on every page view - docs/combine-reduce-proposal.md section 9.1.
 */
public class CombineUnsupportedMemoTest {

	@Test
	public void anUnmarkedGroupIsSupported() {
		assertFalse(new CombineUnsupportedMemo().isUnsupported("http://h/a.htm true"));
	}

	@Test
	public void aMarkedGroupIsUnsupportedUntilItsTtlPasses() throws Exception {

		CombineUnsupportedMemo memo = new CombineUnsupportedMemo();
		memo.mark("http://h/a.htm true", 60000);

		assertTrue(memo.isUnsupported("http://h/a.htm true"));
		assertFalse("the memo is per group, not global", memo.isUnsupported("http://h/b.htm true"));
	}

	/**
	 * The TTL is the whole reason this is a memo and not a flag: an origin gains combine support
	 * mid-deploy, and the edge must find out without a restart.
	 */
	@Test
	public void anExpiredMarkIsForgotten() throws Exception {

		CombineUnsupportedMemo memo = new CombineUnsupportedMemo();
		memo.mark("http://h/a.htm true", 1);

		Thread.sleep(20);

		assertFalse(memo.isUnsupported("http://h/a.htm true"));
		assertEquals("reading an expired mark drops it", 0, memo.size());
	}

	@Test
	public void reMarkingExtendsTheDeadline() throws Exception {

		CombineUnsupportedMemo memo = new CombineUnsupportedMemo();
		memo.mark("g", 1);
		memo.mark("g", 60000);

		Thread.sleep(20);

		assertTrue(memo.isUnsupported("g"));
	}

	@Test
	public void clearForgetsEverything() {

		CombineUnsupportedMemo memo = new CombineUnsupportedMemo();
		memo.mark("g", 60000);
		memo.clear();

		assertFalse(memo.isUnsupported("g"));
	}

	/**
	 * The key space is endpoints named in the origin's own markup - small, and not attacker-chosen -
	 * but entries are only ever written, never read for their own sake, so a group that stops
	 * appearing would otherwise stay forever.
	 */
	@Test
	public void expiredEntriesAreSweptOnceTheMapGrows() throws Exception {

		CombineUnsupportedMemo memo = new CombineUnsupportedMemo();

		for (int i = 0; i < 300; i++)
			memo.mark("group-" + i, 1);

		Thread.sleep(20);
		memo.mark("live", 60000); // crosses the sweep threshold with everything else expired

		assertTrue("the sweep must not drop live entries", memo.isUnsupported("live"));
		assertTrue("expired entries should have been swept, found " + memo.size(), memo.size() < 300);
	}
}
