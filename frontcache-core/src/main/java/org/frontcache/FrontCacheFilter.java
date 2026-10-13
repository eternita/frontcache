/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.license.NodeInstance;


/**
 *
 *
 */
public class FrontCacheFilter implements Filter {

	private FrontCacheEngine fcEngine = null;


	@Override
	public void init(FilterConfig arg0) throws ServletException {

		// before the engine exists, so the instance's first usage record already says how it is
		// deployed - a node in filter mode is one application replica (ip-protection-proposal 4.2)
		NodeInstance.markMode(NodeInstance.Mode.FILTER);

		fcEngine = FrontCacheEngine.getFrontCache();

		return;
	}

	@Override
	public void destroy() {

		fcEngine = null;
		FrontCacheEngine.destroy();
		return;
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {

        try {
            fcEngine.processRequest((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse, chain);
        } catch (Throwable e) {
        	e.printStackTrace();
        	// TODO: handle error
        }

		return;
	}



}

