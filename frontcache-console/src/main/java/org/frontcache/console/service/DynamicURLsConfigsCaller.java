/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import java.util.concurrent.Callable;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.DynamicURLsConfig;

public class DynamicURLsConfigsCaller implements Callable<DynamicURLsConfig> {

	private FrontCacheClient fcClient;

	public DynamicURLsConfigsCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public DynamicURLsConfig call() throws Exception {

		 return new DynamicURLsConfig(fcClient.getName(), fcClient.getDynamicURLs());
    }
}
