/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

public class GetGuardConfigActionResponse extends ActionResponse {

	// raw content of ./conf/guard-rules.conf (comments and empty lines preserved)
	private String guardConfig;

	public GetGuardConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getGuardConfig() {
		return guardConfig;
	}

	public void setGuardConfig(String guardConfig) {
		this.guardConfig = guardConfig;
	}

}
