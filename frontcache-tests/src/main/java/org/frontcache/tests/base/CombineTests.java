/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.frontcache.include.combine.CombineProtocol;
import org.junit.Before;
import org.junit.Test;

import org.htmlunit.html.HtmlPage;

/**
 * Combine-and-reduce, end to end - docs/archive/combine-reduce-proposal.md.
 *
 * Run in both topologies. The standalone run is the one that matters for section 4.1's second
 * claim: the combined request crosses TWO Frontcache nodes, and neither may cache the envelope.
 */
public abstract class CombineTests extends TestsBase {

	public abstract String getFrontCacheBaseURLLocalhost();

	protected FrontCacheClient frontcacheClient = null;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);
		clearCaches();

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");

		resetOriginCounters();
	}

	// --- the feature ---------------------------------------------------------------------------

	/**
	 * The number the whole proposal is about: three cache-missing fragments, ONE origin request.
	 */
	@Test
	public void threeCombinableIncludesCostOneOriginCall() throws Exception {

		HtmlPage page = webClient.getPage(pageURL("page.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals("one origin call for three cells", 1, originInvocations());
		assertEquals("and it was a combined one", 1, combinedInvocations());
	}

	/** The same page without the attribute - the control, and the 3x this exists to remove. */
	@Test
	public void theSameThreeIncludesUncombinedCostThreeOriginCalls() throws Exception {

		HtmlPage page = webClient.getPage(pageURL("page-single.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals(3, originInvocations());
		assertEquals(0, combinedInvocations());
	}

	/**
	 * THE invariant (proposal section 4.2): a combined fetch must leave the cache in the state N
	 * single fetches would have left it - same keys, same bodies.
	 *
	 * Nothing else catches a break here. The fetch URL and the cache key are different strings by
	 * design, so writing the parts under the wrong key throws nothing and shows nothing except a hit
	 * rate that never rises.
	 */
	@Test
	public void combinedAndSingleIncludesLeaveTheSameCache() throws Exception {

		webClient.getPage(pageURL("page.jsp"));
		List<String> combinedKeys = cellKeys();

		clearCaches();
		resetOriginCounters();

		webClient.getPage(pageURL("page-single.jsp"));
		List<String> singleKeys = cellKeys();

		assertEquals("a combined render must cache exactly what an uncombined one caches",
				singleKeys, combinedKeys);
		assertEquals(3, combinedKeys.size());
	}

	@Test
	public void eachPartIsCachedUnderItsOwnUrlWithItsOwnBody() throws Exception {

		webClient.getPage(pageURL("page.jsp"));

		for (int id = 1; id <= 3; id++)
		{
			WebResponse cached = frontcacheClient.getFromCache(cellURL(id));

			assertNotNull("cell " + id + " is not in the cache under its own URL", cached);
			assertEquals("[cell-" + id + "]", new String(cached.getContent(), StandardCharsets.UTF_8));
			assertTrue("the part's invalidation tags must survive the envelope", cached.getTags().contains("cell"));
		}
	}

	/**
	 * Section 4.1: the envelope is marked dynamic so that NO node on the path stores it. Its URL
	 * carries whichever members missed, so caching it would mean one entry per subset - all of them
	 * useless on the next request.
	 */
	@Test
	public void theEnvelopeItselfIsNeverCached() throws Exception {

		webClient.getPage(pageURL("page.jsp"));

		// Both nodes. In the standalone topology the batch crosses two of them, and it is the SECOND
		// one - which sees an ordinary-looking cacheable GET - that a naive implementation lets cache
		// the envelope.
		for (FrontCacheClient node : new FrontCacheClient[]{frontcacheClientStandalone, frontcacheClientFilter})
			for (String key : cachedKeys(node))
				assertFalse("a combined envelope was cached at " + node + " under " + key,
						key.contains(CombineProtocol.PARAM_COMBINE));
	}

	/** Warm cache: nothing missed, so there is nothing to batch and nothing to ask the origin. */
	@Test
	public void aFullyCachedPageAsksTheOriginNothing() throws Exception {

		webClient.getPage(pageURL("page.jsp"));
		assertEquals(1, originInvocations());

		resetOriginCounters();

		HtmlPage page = webClient.getPage(pageURL("page.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals(0, originInvocations());
	}

	/**
	 * The partial-cache case from the proposal's own example: with cell 2 already cached, the batch
	 * carries cells 1 and 3.
	 *
	 * A group down to a single missing member is deliberately NOT batched - the origin does the same
	 * work either way, and the ordinary include path reaches it in one hop instead of two and caches
	 * it through the loopback rather than by hand. That is what the second half pins.
	 */
	@Test
	public void onlyTheCacheMissingMembersAreCombined() throws Exception {

		warmCell(2);
		resetOriginCounters();

		HtmlPage page = webClient.getPage(pageURL("page.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals("cells 1 and 3 in one batch; cell 2 came from the cache", 1, originInvocations());
		assertEquals(1, combinedInvocations());
	}

	@Test
	public void aGroupWithOneMissingMemberIsNotBatched() throws Exception {

		clearCaches();
		warmCell(1);
		warmCell(2);
		resetOriginCounters();

		HtmlPage page = webClient.getPage(pageURL("page.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals(1, originInvocations());
		assertEquals("one missing member is an ordinary include, not a batch of one",
				0, combinedInvocations());
	}

	/**
	 * Fetch a cell directly, so it lands in the cache under exactly the key an include for it uses -
	 * an include URL is built from the edge's own base URL, which is the one being requested here.
	 */
	private void warmCell(int id) throws Exception {
		webClient.getPage(cellURL(id));
		assertNotNull("cell " + id + " did not warm", frontcacheClient.getFromCache(cellURL(id)));
	}

	// --- degradation ---------------------------------------------------------------------------

	/**
	 * Section 9.1. An origin carrying the attribute but not the handler must still render the page -
	 * that is the whole reason the support signal is a response header and not an assumption.
	 */
	@Test
	public void anOriginWithoutACombineHandlerStillRendersThePage() throws Exception {

		HtmlPage page = webClient.getPage(pageURL("page-unsupported.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals("the probe, plus the three individual fetches it fell back to", 4, originInvocations());
	}

	/** And the memo means the next view of that page does not pay the probe again. */
	@Test
	public void theUnsupportedProbeIsNotPaidTwice() throws Exception {

		webClient.getPage(pageURL("page-unsupported2.jsp"));

		clearCaches();
		resetOriginCounters();

		HtmlPage page = webClient.getPage(pageURL("page-unsupported2.jsp"));

		assertEquals("[cell-1][cell-2][cell-3]", page.getPage().asNormalizedText());
		assertEquals("three fetches and no probe - the group is memoised as unsupported",
				3, originInvocations());
	}

	/**
	 * Section 9.2, the short envelope. A count mismatch means the parts no longer line up with the
	 * members, so every member of the batch falls back rather than being spliced one position out.
	 */
	@Test
	public void aShortEnvelopeIsRejectedRatherThanMisaligned() throws Exception {

		HtmlPage page = webClient.getPage(pageURL("page-short.jsp"));

		String rendered = page.getPage().asNormalizedText();

		assertFalse("a two-part answer to a three-member batch must not be spliced: " + rendered,
				rendered.contains("[cell-1][cell-2][cell-3]"));

		for (int id = 1; id <= 3; id++)
			assertNull("nothing from a rejected envelope may be cached (cell " + id + ")",
					frontcacheClient.getFromCache(shortCellURL(id)));
	}

	/**
	 * Section 6, rule 3 - the {@code q} echo, and the bug class it exists for: an origin that
	 * reorders its parts renders a page where every cell shows another cell's content. Nothing about
	 * that looks like an error, which is why the echo is checked for the whole envelope before any
	 * part of it is applied.
	 */
	@Test
	public void aShuffledEnvelopeIsCaughtByTheEcho() throws Exception {

		HtmlPage page = webClient.getPage(pageURL("page-shuffled.jsp"));

		String rendered = page.getPage().asNormalizedText();

		assertFalse("reordered parts must not be spliced: " + rendered, rendered.contains("[cell-3][cell-2][cell-1]"));

		for (int id = 1; id <= 3; id++)
			assertNull("a reordered envelope must cache nothing (cell " + id + ")",
					frontcacheClient.getFromCache(shuffledCellURL(id)));
	}

	// --- plumbing ------------------------------------------------------------------------------

	private String pageURL(String page) {
		return getFrontCacheBaseURLLocalhost() + "common/combine/" + page;
	}

	private String cellURL(int id) {
		return getFrontCacheBaseURLLocalhost() + "common/combine/cell?id=" + id;
	}

	private String shortCellURL(int id) {
		return getFrontCacheBaseURLLocalhost() + "common/combine/cell-short?id=" + id;
	}

	private String shuffledCellURL(int id) {
		return getFrontCacheBaseURLLocalhost() + "common/combine/cell-shuffled?id=" + id;
	}

	/** The cached cell keys, sorted, so two renders can be compared as sets rather than as orders. */
	private List<String> cellKeys() throws Exception {

		List<String> keys = new ArrayList<String>();
		for (String key : cachedKeys(frontcacheClient))
			if (key.contains("/common/combine/cell"))
				keys.add(key);

		java.util.Collections.sort(keys);
		return keys;
	}

	private List<String> cachedKeys(FrontCacheClient node) throws Exception {

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		node.getCachedKeys(baos);

		List<String> keys = new ArrayList<String>();
		for (String line : new String(baos.toByteArray(), StandardCharsets.UTF_8).split("\n"))
			if (0 < line.trim().length())
				keys.add(line.trim());

		return keys;
	}

	/**
	 * Empty BOTH nodes. In the standalone topology a fragment fetched the ordinary way is cached at
	 * the edge AND at the webfilter node behind it, so clearing only the edge leaves the second one
	 * answering - and the origin counter never moves.
	 */
	private void clearCaches() {
		frontcacheClientStandalone.removeFromCacheAll();
		frontcacheClientFilter.removeFromCacheAll();
	}

	private void resetOriginCounters() throws Exception {
		readCounters("?reset=1");
	}

	private int originInvocations() throws Exception {
		return counter(readCounters(""), "invocations=");
	}

	private int combinedInvocations() throws Exception {
		return counter(readCounters(""), "combined=");
	}

	/**
	 * Read straight through the edge - the counters endpoint declares no maxage, so no node on the
	 * path stores it and the number is always the live one.
	 */
	private String readCounters(String query) throws Exception {
		org.htmlunit.Page counters = webClient.getPage(pageURL("counters") + query);
		return counters.getWebResponse().getContentAsString();
	}

	private static int counter(String counters, String name) {

		for (String token : counters.trim().split(" "))
			if (token.startsWith(name))
				return Integer.parseInt(token.substring(name.length()));

		throw new IllegalStateException("no " + name + " in \"" + counters + "\"");
	}
}
