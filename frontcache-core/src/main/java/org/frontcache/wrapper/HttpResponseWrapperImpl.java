/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.wrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 *
 * Wrapper for response
 *
 */
public class HttpResponseWrapperImpl extends HttpServletResponseWrapper implements FrontCacheHttpResponseWrapper {
	int BUFFER_SIZE = 4000;
	private StringWriter sw = new StringWriter(BUFFER_SIZE);

	public HttpResponseWrapperImpl(HttpServletResponse response) {
		super(response);
	}

	public PrintWriter getWriter() throws IOException {
		return new PrintWriter(sw);
	}

	public String getContentString() {
		return sw.toString();
	}

}