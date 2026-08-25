/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

/**
 * Raw content of ./conf/resilience.properties (comments and empty lines preserved), for display in
 * the console.
 *
 * Carries the value under BOTH the current {@code resilienceConfig} name and the pre-2.7
 * {@code hystrixConfig} one, because the console is deployed separately from the nodes it reads: a
 * 2.6 console must keep working against a 2.7 node, and a 2.7 console must keep working against a
 * 2.6 node. Emitting both costs one duplicated string in a config dump and removes the whole class
 * of upgrade-ordering problem.
 *
 * The legacy field is also accepted on the way IN, so this class can parse a 2.6 node's response.
 */
public class GetResilienceConfigActionResponse extends ActionResponse {

	private String resilienceConfig;

	public GetResilienceConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getResilienceConfig() {
		return resilienceConfig;
	}

	public void setResilienceConfig(String resilienceConfig) {
		this.resilienceConfig = resilienceConfig;
	}

	/**
	 * @deprecated pre-2.7 spelling of {@link #getResilienceConfig()}. Still serialized so an older
	 *             console keeps rendering this panel; read the current name in new code.
	 */
	@Deprecated
	public String getHystrixConfig() {
		return resilienceConfig;
	}

	/**
	 * Accepts a pre-2.7 node's response. Guarded so field order in the JSON cannot matter: whichever
	 * of the two names Jackson hands us first wins, and the current one is never overwritten by the
	 * legacy one.
	 *
	 * @deprecated pre-2.7 spelling of {@link #setResilienceConfig(String)}
	 */
	@Deprecated
	public void setHystrixConfig(String hystrixConfig) {
		if (null == resilienceConfig)
			resilienceConfig = hystrixConfig;
	}

}
