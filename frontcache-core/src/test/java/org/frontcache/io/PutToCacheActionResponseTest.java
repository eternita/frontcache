/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for the {@code put-to-cache} IO action response model and action constant.
 */
public class PutToCacheActionResponseTest {

	private final ObjectMapper jsonMapper = new ObjectMapper();

	@Test
	public void putToCacheActionConstantExists() {
		assertEquals("put-to-cache", FrontcacheAction.PUT_TO_CACHE);
	}

	@Test
	public void putToCacheActionInDescriptionMap() {
		Map<String, String> desc = FrontcacheAction.actionsDescriptionMap;
		assertTrue("PUT_TO_CACHE must be in actionsDescriptionMap",
				desc.containsKey(FrontcacheAction.PUT_TO_CACHE));
		assertNotNull("description must not be null",
				desc.get(FrontcacheAction.PUT_TO_CACHE));
	}

	@Test
	public void responseSerializesToJson() throws Exception {
		PutToCacheActionResponse resp = new PutToCacheActionResponse("/en/welcome.htm", "www.example.com");

		String json = jsonMapper.writeValueAsString(resp);

		assertTrue(json.contains("\"action\":\"put to cache\""));
		assertTrue(json.contains("\"responseStatus\":\"OK\""));
		assertTrue(json.contains("\"key\":\"/en/welcome.htm\""));
		assertTrue(json.contains("\"domain\":\"www.example.com\""));
	}

	@Test
	public void responseDeserializesFromJson() throws Exception {
		String json = "{\"action\":\"put to cache\",\"responseStatus\":\"OK\","
				+ "\"key\":\"/en/welcome.htm\",\"domain\":\"www.example.com\"}";

		PutToCacheActionResponse resp = jsonMapper.readValue(json.getBytes(), PutToCacheActionResponse.class);

		assertEquals("put to cache", resp.getAction());
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, resp.getResponseStatus());
		assertEquals("/en/welcome.htm", resp.getKey());
		assertEquals("www.example.com", resp.getDomain());
	}

	@Test
	public void noArgConstructorForJackson() throws Exception {
		// Jackson needs a no-arg constructor to deserialize
		PutToCacheActionResponse resp = jsonMapper.readValue(
				"{\"action\":\"put to cache\",\"responseStatus\":\"OK\"}".getBytes(),
				PutToCacheActionResponse.class);

		assertNotNull(resp);
		assertNull(resp.getKey());
		assertNull(resp.getDomain());
	}

	@Test
	public void errorResponseRoundTrip() throws Exception {
		PutToCacheActionResponse resp = new PutToCacheActionResponse("/missing.htm", "www.example.com");
		resp.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
		resp.setErrorDescription("Entry not found");

		String json = jsonMapper.writeValueAsString(resp);
		PutToCacheActionResponse back = jsonMapper.readValue(json.getBytes(), PutToCacheActionResponse.class);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, back.getResponseStatus());
		assertEquals("Entry not found", back.getErrorDescription());
		assertEquals("/missing.htm", back.getKey());
	}
}
