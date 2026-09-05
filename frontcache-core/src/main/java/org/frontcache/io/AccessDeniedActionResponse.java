/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

public class AccessDeniedActionResponse extends ActionResponse {

	public AccessDeniedActionResponse() {
		setResponseStatus(RESPONSE_STATUS_ERROR);
		setErrorDescription("Access denied to Management URI. Send this node's front-cache.site-key as"
				+ " 'Authorization: Bearer <site-key>'.");
	}
}
