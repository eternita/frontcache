/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.module;

/**
 * How this node is deployed, as {@link FrontcacheModule#onModeKnown} reports it: behind
 * {@code FrontCacheServlet} (a reverse proxy) or {@code FrontCacheFilter} (inside the origin application).
 */
public enum NodeMode {
	STANDALONE, FILTER
}
