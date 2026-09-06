/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

public class ReloadBotsActionResponse extends ActionResponse {

	public ReloadBotsActionResponse() { // for JSON mapper
		setAction("Frontcache bot classification rules have been reloaded (hit counters reset)");
		setResponseStatus(RESPONSE_STATUS_OK);
	}

}
