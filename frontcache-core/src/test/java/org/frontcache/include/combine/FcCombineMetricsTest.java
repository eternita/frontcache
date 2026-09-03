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
import static org.junit.Assert.assertTrue;

import java.util.TreeSet;

import org.junit.After;
import org.junit.Test;

/**
 * The combine counters - docs/archive/combine-reduce-proposal.md section 12.
 */
public class FcCombineMetricsTest {

	@After
	public void tearDown() {
		FcCombineMetrics.reset();
	}

	@Test
	public void batchOutcomesAreCountedSeparately() {

		FcCombineMetrics.record(FcCombineMetrics.Batch.OK);
		FcCombineMetrics.record(FcCombineMetrics.Batch.OK);
		FcCombineMetrics.record(FcCombineMetrics.Batch.UNSUPPORTED);

		assertEquals(2, FcCombineMetrics.count(FcCombineMetrics.Batch.OK));
		assertEquals(1, FcCombineMetrics.count(FcCombineMetrics.Batch.UNSUPPORTED));
		assertEquals(0, FcCombineMetrics.count(FcCombineMetrics.Batch.ERROR));
		assertEquals(0, FcCombineMetrics.count(FcCombineMetrics.Batch.FAILED));
	}

	/** The batch paths resolve members in bulk, so the bulk form has to add rather than set. */
	@Test
	public void membersAreCountedInBulkAndSinglyAgainstTheSameTotal() {

		FcCombineMetrics.record(FcCombineMetrics.Member.COMBINED, 12);
		FcCombineMetrics.record(FcCombineMetrics.Member.COMBINED, 3);
		FcCombineMetrics.record(FcCombineMetrics.Member.COMBINED);

		assertEquals(16, FcCombineMetrics.count(FcCombineMetrics.Member.COMBINED));
	}

	@Test
	public void errorKindsAreCountedSeparately() {

		FcCombineMetrics.record(FcCombineMetrics.Error.PARSE);
		FcCombineMetrics.record(FcCombineMetrics.Error.ECHO);
		FcCombineMetrics.record(FcCombineMetrics.Error.ECHO);

		assertEquals(1, FcCombineMetrics.count(FcCombineMetrics.Error.PARSE));
		assertEquals(2, FcCombineMetrics.count(FcCombineMetrics.Error.ECHO));
		assertEquals(0, FcCombineMetrics.count(FcCombineMetrics.Error.COUNT));
	}

	@Test
	public void resetClearsEveryFamily() {

		FcCombineMetrics.record(FcCombineMetrics.Batch.OK);
		FcCombineMetrics.record(FcCombineMetrics.Member.CACHED, 5);
		FcCombineMetrics.record(FcCombineMetrics.Error.COUNT);

		FcCombineMetrics.reset();

		assertEquals(0, FcCombineMetrics.count(FcCombineMetrics.Batch.OK));
		assertEquals(0, FcCombineMetrics.count(FcCombineMetrics.Member.CACHED));
		assertEquals(0, FcCombineMetrics.count(FcCombineMetrics.Error.COUNT));
	}

	/**
	 * Label values are the series' public surface: a Grafana panel or an alert rule is written against
	 * these strings, so a duplicate would silently merge two outcomes into one series and a rename
	 * would break a dashboard that has no way to notice.
	 */
	@Test
	public void everyLabelValueIsDistinctAndStable() {

		assertDistinct(FcCombineMetrics.Batch.values().length,
				FcCombineMetrics.Batch.OK.getResult(),
				FcCombineMetrics.Batch.UNSUPPORTED.getResult(),
				FcCombineMetrics.Batch.ERROR.getResult(),
				FcCombineMetrics.Batch.FAILED.getResult());

		assertDistinct(FcCombineMetrics.Member.values().length,
				FcCombineMetrics.Member.CACHED.getResult(),
				FcCombineMetrics.Member.COMBINED.getResult(),
				FcCombineMetrics.Member.INDIVIDUAL.getResult(),
				FcCombineMetrics.Member.FALLBACK.getResult());

		assertDistinct(FcCombineMetrics.Error.values().length,
				FcCombineMetrics.Error.PARSE.getKind(),
				FcCombineMetrics.Error.COUNT.getKind(),
				FcCombineMetrics.Error.ECHO.getKind());

		// spelled out, so a rename shows up here rather than on somebody's dashboard
		assertEquals("ok", FcCombineMetrics.Batch.OK.getResult());
		assertEquals("unsupported", FcCombineMetrics.Batch.UNSUPPORTED.getResult());
		assertEquals("combined", FcCombineMetrics.Member.COMBINED.getResult());
		assertEquals("cached", FcCombineMetrics.Member.CACHED.getResult());
		assertEquals("individual", FcCombineMetrics.Member.INDIVIDUAL.getResult());
		assertEquals("fallback", FcCombineMetrics.Member.FALLBACK.getResult());
		assertEquals("echo", FcCombineMetrics.Error.ECHO.getKind());
	}

	private static void assertDistinct(int expected, String... values) {

		TreeSet<String> distinct = new TreeSet<String>(java.util.Arrays.asList(values));

		assertEquals("label values collide: " + distinct, expected, distinct.size());
		for (String value : distinct)
			assertTrue("a label value must be a plain lower-case token, got " + value,
					value.matches("[a-z][a-z-]*"));
	}
}
