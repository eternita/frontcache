/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.module;

import java.io.File;

import org.frontcache.cache.CacheProcessor;

/**
 * What a {@link FrontcacheModule} may read from the node - the things module code would otherwise reach
 * for through a core class's statics.
 */
public interface ModuleContext {

	/**
	 * The site's public hostname ({@code front-cache.default-domain}), or null when the node has none
	 * configured - never the placeholder.
	 */
	String publicDomain();

	/** FRONTCACHE_HOME, or null for a node started without one. */
	File frontcacheHome();

	/** A property of {@code frontcache.properties}, read-only; null when unset. */
	String property(String key);

	/** The node's cache processor if it exists, without creating one; may be null. */
	CacheProcessor existingCacheProcessor();

	/**
	 * The in-memory token a warm request presents in {@code x-frontcache-warm} - see
	 * {@code org.frontcache.warmer.WarmRequest}. In-process only: it must never be logged or sent anywhere
	 * but to this node's own loopback.
	 */
	String warmRequestToken();
}
