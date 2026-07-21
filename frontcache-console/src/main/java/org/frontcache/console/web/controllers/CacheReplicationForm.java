/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

/**
 * Form bean for the Cache Replication screen.
 * Holds the source and target edge URLs selected by the operator.
 */
public class CacheReplicationForm {

	private String sourceEdge;
	private String targetEdge;

	public CacheReplicationForm() {
	}

	public String getSourceEdge() {
		return sourceEdge;
	}

	public void setSourceEdge(String sourceEdge) {
		this.sourceEdge = sourceEdge;
	}

	public String getTargetEdge() {
		return targetEdge;
	}

	public void setTargetEdge(String targetEdge) {
		this.targetEdge = targetEdge;
	}

}
