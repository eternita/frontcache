/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.htmlunit.Page;
import org.htmlunit.WebResponse;

public abstract class HTTPResponseCodeTests extends TestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	public abstract String getFrontCacheBaseURLLocalhost();

	@Test
	public void code404() throws Exception {
		Page page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "something/what-doesnt-exist");
		WebResponse webResponse = page.getWebResponse();
		printHeaders(webResponse);

		Assert.assertEquals(404, webResponse.getStatusCode());
	}

}
