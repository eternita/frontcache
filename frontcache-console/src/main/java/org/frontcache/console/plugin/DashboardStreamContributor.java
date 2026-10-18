/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.plugin;

import java.util.List;

import org.frontcache.console.model.DashboardStream;

/**
 * A root-context bean that adds to the edge list the realtime page receives - today, the Pro plugin's
 * cluster membership ({@link DashboardStream#setCluster}). With none, every stream's cluster is null and
 * the page renders exactly as an unclustered console does.
 */
public interface DashboardStreamContributor {

	/** Called on every realtime page render, with the streams in the console's edge order. */
	void contribute(List<DashboardStream> streams);
}
