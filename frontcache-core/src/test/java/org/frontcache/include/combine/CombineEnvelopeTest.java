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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.frontcache.core.FCHeaders;
import org.junit.Test;

/**
 * The combine wire contract's envelope half (docs/combine-reduce-proposal.md section 6).
 */
public class CombineEnvelopeTest {

	@Test
	public void aWellFormedEnvelopeParses() throws Exception {

		String json = "{\"v\":1,\"parts\":["
				+ "{\"q\":\"id=1\",\"status\":200,"
				+ " \"headers\":{\"Content-Type\":[\"text/html;charset=UTF-8\"],"
				+ "              \"x-frontcache-component-maxage\":[\"10d\"]},"
				+ " \"body\":\"<div>one</div>\"},"
				+ "{\"q\":\"id=3\",\"body\":\"<div>three</div>\"}]}";

		CombineEnvelope envelope = CombineEnvelope.fromJson(json);

		assertEquals(1, envelope.getV());
		assertEquals(2, envelope.getParts().size());

		CombinePart first = envelope.getParts().get(0);
		assertEquals("id=1", first.getQ());
		assertEquals(200, first.getStatus());
		assertEquals("<div>one</div>", first.getBody());
		assertEquals(Arrays.asList("10d"), first.getHeaders().get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE));
	}

	/** Status defaults to 200 and headers to absent, so the minimal part an origin can emit is a body. */
	@Test
	public void aMinimalPartDefaultsToTwoHundredAndNoHeaders() throws Exception {

		CombinePart part = CombineEnvelope.fromJson("{\"parts\":[{\"body\":\"x\"}]}").getParts().get(0);

		assertEquals(200, part.getStatus());
		assertNull(part.getHeaders());
		assertNull("the echo is optional", part.getQ());
	}

	/**
	 * The forward-compatibility rule, and the reason the edge and the origin can be upgraded in
	 * either order: an unknown field is free, at both levels.
	 */
	@Test
	public void unknownFieldsAreIgnoredAtBothLevels() throws Exception {

		String json = "{\"v\":2,\"generatedBy\":\"origin-2.10\",\"parts\":"
				+ "[{\"body\":\"x\",\"renderMillis\":7,\"trace\":{\"sql\":5}}]}";

		CombineEnvelope envelope = CombineEnvelope.fromJson(json);

		assertEquals(2, envelope.getV());
		assertEquals("x", envelope.getParts().get(0).getBody());
	}

	@Test
	public void aNonTwoHundredPartSurvivesParsing() throws Exception {

		CombinePart part = CombineEnvelope.fromJson("{\"parts\":[{\"q\":\"id=9\",\"status\":404}]}").getParts().get(0);

		assertEquals(404, part.getStatus());
		assertNull(part.getBody());
	}

	@Test
	public void malformedJsonThrowsRatherThanReturningAnEmptyEnvelope() {

		try {
			CombineEnvelope.fromJson("{\"v\":1,\"parts\":[{\"body\":\"trunca");
			fail("a truncated envelope must not parse to an empty or partial one - the edge decides what to do "
					+ "about a protocol error, and it cannot decide about one it was not told of");
		} catch (Exception expected) {
			// expected
		}
	}

	@Test
	public void utf8BodiesSurviveTheByteEntryPoint() throws Exception {

		String json = "{\"parts\":[{\"body\":\"<div>монета — 5 €</div>\"}]}";

		CombineEnvelope envelope = CombineEnvelope.fromJson(json.getBytes(StandardCharsets.UTF_8));

		assertEquals("<div>монета — 5 €</div>", envelope.getParts().get(0).getBody());
	}

	/**
	 * The origin-side builder and the edge-side parser, across the seam. An origin that uses
	 * {@link CombineResponse} cannot emit something the edge rejects.
	 */
	@Test
	public void whatTheOriginBuildsIsWhatTheEdgeReads() throws Exception {

		CombineResponse out = new CombineResponse();
		out.addComponent("id=1", "<div>one</div>", "10d", "coin|coin-1");
		out.addError("id=2", 500);
		out.add("<div>three</div>");

		CombineEnvelope envelope = CombineEnvelope.fromJson(out.toJson());

		List<CombinePart> parts = envelope.getParts();
		assertEquals(3, parts.size());

		assertEquals("id=1", parts.get(0).getQ());
		assertEquals(Arrays.asList("10d"), parts.get(0).getHeaders().get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE));
		assertEquals(Arrays.asList("coin|coin-1"), parts.get(0).getHeaders().get(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS));

		assertEquals(500, parts.get(1).getStatus());
		assertNull(parts.get(1).getBody());

		assertEquals(200, parts.get(2).getStatus());
		assertNull("a part with nothing to declare declares nothing", parts.get(2).getHeaders());
	}

	/** Null fields are omitted, so the common part - body only - is not padded with nulls. */
	@Test
	public void nullFieldsAreNotSerialised() throws Exception {

		String json = new CombineResponse().add("<b>x</b>").toJson();

		assertNotNull(json);
		assertTrue(json, json.contains("\"body\":\"<b>x</b>\""));
		assertTrue("no echo was given, so none is sent: " + json, !json.contains("\"q\""));
		assertTrue("no headers were given, so none are sent: " + json, !json.contains("\"headers\""));
	}
}
