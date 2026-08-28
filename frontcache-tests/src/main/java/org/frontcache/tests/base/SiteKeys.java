/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

/**
 * The node's management-API site key. There is one per node - per-domain site keys went away with
 * multi-domain support (see docs/archive/multi-domain-retirement-proposal.md).
 */
public interface SiteKeys {
	public static final String TEST_SITE_KEY_LOCALHOST = "test-site-key-localhost";
}
