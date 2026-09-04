/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.core.RequestContext;
import org.frontcache.include.combine.CombineProtocol;
import org.frontcache.include.combine.CombineUnsupportedMemo;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Which includes end up in a combined batch, and how a batch is chunked.
 * See docs/archive/combine-reduce-proposal.md sections 8 and 11.
 *
 * These sit on the decision, not on the origin call: everything here runs without a cache, a client
 * or a server. The origin round trip is the e2e suite's job.
 */
public class CombinePartitionTest {

	private static final String HOST = "http://www.example.com:8080";

	private ConcurrentIncludeProcessor processor;

	@Before
	public void setUp() {
		processor = newProcessor(new Properties());
	}

	@After
	public void tearDown() {
		if (null != processor)
			processor.destroy();
	}

	private static ConcurrentIncludeProcessor newProcessor(Properties properties) {
		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(properties);
		return processor;
	}

	// --- the combine attribute ----------------------------------------------------------------

	@Test
	public void combineTrueMarksAnIncludeCombinable() throws Exception {
		assertEquals("true", combineGroupOf("<fc:include url=\"/fc/a.htm?id=1\" combine=\"true\" />"));
	}

	@Test
	public void aNamedGroupIsCarriedThrough() throws Exception {
		assertEquals("search-cell", combineGroupOf("<fc:include url=\"/fc/a.htm?id=1\" combine=\"search-cell\" />"));
	}

	@Test
	public void anIncludeWithoutTheAttributeIsNotCombinable() throws Exception {
		assertNull(combineGroupOf("<fc:include url=\"/fc/a.htm?id=1\" />"));
	}

	@Test
	public void combineFalseAndCombineEmptyAreNotCombinable() throws Exception {
		assertNull(combineGroupOf("<fc:include url=\"/fc/a.htm\" combine=\"false\" />"));
		assertNull(combineGroupOf("<fc:include url=\"/fc/a.htm\" combine=\"\" />"));
	}

	/**
	 * Nothing waits for an async include and its content never reaches the client, so batching it
	 * buys nothing - and would hand it a shared deadline it does not currently have.
	 */
	@Test
	public void anAsyncIncludeIsNeverCombinedEvenWhenMarked() throws Exception {
		assertNull(combineGroupOf("<fc:include url=\"/fc/a.htm\" call=\"async\" combine=\"true\" />"));
	}

	@Test
	public void theAttributeIsIndependentOfItsPositionInTheTag() throws Exception {
		assertEquals("cell", combineGroupOf("<fc:include combine=\"cell\" url=\"/fc/a.htm\" client=\"bot\" />"));
	}

	// --- isCombinable -------------------------------------------------------------------------

	@Test
	public void aMarkedIncludeIsCombinable() throws Exception {
		assertTrue(isCombinable(processor, placeholder("<fc:include url=\"/fc/a.htm?id=1\" combine=\"true\" />")));
	}

	@Test
	public void anUnmarkedIncludeIsNotCombinable() throws Exception {
		assertFalse(isCombinable(processor, placeholder("<fc:include url=\"/fc/a.htm?id=1\" />")));
	}

	/**
	 * An {@code fc-} parameter of the include's own would arrive at the origin beside the protocol's,
	 * and the origin cannot tell them apart. Refusing to combine is the only answer that cannot be
	 * silently wrong.
	 */
	@Test
	public void anIncludeCarryingAReservedParameterIsNotCombinable() throws Exception {
		assertFalse(isCombinable(processor,
				placeholder("<fc:include url=\"/fc/a.htm?id=1&fc-parts=9\" combine=\"true\" />")));
	}

	@Test
	public void theKillSwitchTurnsCombiningOff() throws Exception {

		Properties off = new Properties();
		off.setProperty("front-cache.include-processor.impl.concurrent.combine", "false");

		ConcurrentIncludeProcessor disabled = newProcessor(off);
		try {
			assertFalse(isCombinable(disabled, placeholder("<fc:include url=\"/fc/a.htm?id=1\" combine=\"true\" />")));
		} finally {
			disabled.destroy();
		}
	}

	@Test
	public void aGroupMemoisedAsUnsupportedIsNotCombinable() throws Exception {

		Object inc = placeholder("<fc:include url=\"/fc/a.htm?id=1\" combine=\"true\" />");

		assertTrue(isCombinable(processor, inc));

		memoOf(processor).mark(CombineProtocol.groupKey(HOST + "/fc/a.htm?id=1", "true"), 60000);

		assertFalse(isCombinable(processor, inc));
	}

	/** A reload may change the TTL, so deadlines measured against the old one must not survive it. */
	@Test
	public void initClearsTheMemo() throws Exception {

		ConcurrentIncludeProcessor reloaded = newProcessor(new Properties());
		try {
			memoOf(reloaded).mark("g", 60000);

			reloaded.destroy(); // init() replaces the executor - shut the first one down before it does
			reloaded.init(new Properties());

			assertFalse(memoOf(reloaded).isUnsupported("g"));
		} finally {
			reloaded.destroy();
		}
	}

	// --- chunking -----------------------------------------------------------------------------

	@Test
	public void aBatchIsSplitOnTheMemberCount() throws Exception {

		Properties props = new Properties();
		props.setProperty("front-cache.include-processor.impl.concurrent.combine.max-batch-size", "4");

		ConcurrentIncludeProcessor small = newProcessor(props);
		try {
			List<List<String>> chunks = chunkUrls(small, urls(10));

			assertEquals(3, chunks.size());
			assertEquals(4, chunks.get(0).size());
			assertEquals(4, chunks.get(1).size());
			assertEquals(2, chunks.get(2).size());
		} finally {
			small.destroy();
		}
	}

	/**
	 * The second cap. A combined query past the proxy's request-line limit comes back as a 414, which
	 * looks like nothing else in the logs - so the split happens here rather than being discovered
	 * there.
	 */
	@Test
	public void aBatchIsSplitOnTheEncodedQueryLength() throws Exception {

		Properties props = new Properties();
		props.setProperty("front-cache.include-processor.impl.concurrent.combine.max-batch-size", "1000");
		props.setProperty("front-cache.include-processor.impl.concurrent.combine.max-url-length", "120");

		ConcurrentIncludeProcessor short_ = newProcessor(props);
		try {
			List<List<String>> chunks = chunkUrls(short_, urls(20));

			assertTrue("20 members must not fit in 120 characters", 1 < chunks.size());

			int total = 0;
			for (List<String> chunk : chunks)
			{
				assertFalse("a chunk must never be empty", chunk.isEmpty());
				total += chunk.size();
			}
			assertEquals("every member lands in exactly one chunk", 20, total);
		} finally {
			short_.destroy();
		}
	}

	/**
	 * A single member longer than the cap still has to be sent: dropping it would lose the fragment,
	 * and splitting it is not a thing that exists. One over-long chunk beats no chunk.
	 */
	@Test
	public void aSingleOversizedMemberIsStillSentAlone() throws Exception {

		Properties props = new Properties();
		props.setProperty("front-cache.include-processor.impl.concurrent.combine.max-url-length", "10");

		ConcurrentIncludeProcessor tiny = newProcessor(props);
		try {
			List<List<String>> chunks = chunkUrls(tiny, urls(3));

			assertEquals(3, chunks.size());
			for (List<String> chunk : chunks)
				assertEquals(1, chunk.size());
		} finally {
			tiny.destroy();
		}
	}

	@Test
	public void chunkingPreservesPageOrder() throws Exception {

		Properties props = new Properties();
		props.setProperty("front-cache.include-processor.impl.concurrent.combine.max-batch-size", "3");

		ConcurrentIncludeProcessor small = newProcessor(props);
		try {
			List<String> flattened = new ArrayList<String>();
			for (List<String> chunk : chunkUrls(small, urls(7)))
				flattened.addAll(chunk);

			assertEquals(urls(7), flattened);
		} finally {
			small.destroy();
		}
	}

	@Test
	public void aNonNumericCapFallsBackToTheDefaultRatherThanFailingToStart() throws Exception {

		Properties props = new Properties();
		props.setProperty("front-cache.include-processor.impl.concurrent.combine.max-batch-size", "lots");

		ConcurrentIncludeProcessor lenient = newProcessor(props);
		try {
			assertEquals("the 30-member default", 1, chunkUrls(lenient, urls(30)).size());
			assertEquals(2, chunkUrls(lenient, urls(31)).size());
		} finally {
			lenient.destroy();
		}
	}

	// --- plumbing -----------------------------------------------------------------------------

	private static List<String> urls(int count) {
		List<String> urls = new ArrayList<String>(count);
		for (int i = 0; i < count; i++)
			urls.add(HOST + "/fc/search-item.htm?id=" + i + "&lang=en");
		return urls;
	}

	private String combineGroupOf(String includeTag) throws Exception {
		Object inc = placeholder(includeTag);
		Field f = inc.getClass().getDeclaredField("combineGroup");
		f.setAccessible(true);
		return (String) f.get(inc);
	}

	/** {@code parseIncludes} is private, and the placeholder is an inner class - reflection is the only door. */
	private Object placeholder(String includeTag) throws Exception {

		Method parseIncludes = null;
		for (Method candidate : ConcurrentIncludeProcessor.class.getDeclaredMethods())
			if ("parseIncludes".equals(candidate.getName()))
				parseIncludes = candidate;

		parseIncludes.setAccessible(true);

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		headers.put("Host", Arrays.asList(new String[]{"www.example.com"}));

		@SuppressWarnings("unchecked")
		List<Object> includes = (List<Object>) parseIncludes.invoke(processor,
				"<html>" + includeTag + "</html>", HOST, headers, null, new RequestContext());

		return includes.get(0);
	}

	private static boolean isCombinable(ConcurrentIncludeProcessor processor, Object placeholder) throws Exception {

		Method m = null;
		for (Method candidate : ConcurrentIncludeProcessor.class.getDeclaredMethods())
			if ("isCombinable".equals(candidate.getName()))
				m = candidate;

		m.setAccessible(true);
		return ((Boolean) m.invoke(processor, placeholder)).booleanValue();
	}

	@SuppressWarnings("unchecked")
	private static List<List<String>> chunkUrls(ConcurrentIncludeProcessor processor, Collection<String> urls) throws Exception {

		Method m = ConcurrentIncludeProcessor.class.getDeclaredMethod("chunkUrls", Collection.class);
		m.setAccessible(true);
		return (List<List<String>>) m.invoke(processor, urls);
	}

	private static CombineUnsupportedMemo memoOf(ConcurrentIncludeProcessor processor) throws Exception {

		Field f = ConcurrentIncludeProcessor.class.getDeclaredField("combineUnsupportedMemo");
		f.setAccessible(true);
		return (CombineUnsupportedMemo) f.get(processor);
	}
}
