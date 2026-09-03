/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include.combine;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per batch group, that the origin does not implement combining.
 * See docs/archive/combine-reduce-proposal.md section 9.1.
 *
 * Without it, an origin that has the {@code combine} attribute in its markup but not the handler
 * behind it pays one wasted request per group on every single page view, forever. With it, that
 * cost is one probe per group per TTL, and a rolling origin deploy converges within one TTL rather
 * than staying degraded until someone notices.
 *
 * <h3>Why a TTL and not a permanent flag</h3>
 *
 * The answer changes. An origin gains combine support mid-deploy, and half the fleet answers one
 * way while the other half answers the other. A permanent flag would pin the edge to whichever
 * node it happened to ask first.
 *
 * <h3>Bounding it</h3>
 *
 * The key space is endpoints named in the origin's own markup, so it is small and not
 * attacker-chosen - but it is written from include threads and never otherwise read, so an entry
 * for a group that stops appearing would live forever. {@link #mark} sweeps expired entries once
 * the map grows past {@link #SWEEP_THRESHOLD}, which is cheap because the sweep only ever runs on
 * the already-slow unsupported path.
 */
public class CombineUnsupportedMemo {

	/** Entry count past which {@link #mark} sweeps expired keys. Groups are per-endpoint; this is roomy. */
	private static final int SWEEP_THRESHOLD = 256;

	private final Map<String, Long> unsupportedUntil = new ConcurrentHashMap<String, Long>();

	/** Is combining known to be unsupported for this group right now? */
	public boolean isUnsupported(String groupKey) {

		Long until = unsupportedUntil.get(groupKey);
		if (null == until)
			return false;

		if (System.currentTimeMillis() < until.longValue())
			return true;

		// Expired. Remove conditionally so a concurrent mark() with a fresher deadline is not lost.
		unsupportedUntil.remove(groupKey, until);
		return false;
	}

	/** Record that this group's origin did not understand a combined request. */
	public void mark(String groupKey, long ttlMillis) {

		unsupportedUntil.put(groupKey, Long.valueOf(System.currentTimeMillis() + ttlMillis));

		if (SWEEP_THRESHOLD < unsupportedUntil.size())
			sweep();
	}

	/** Forget everything - for tests, and for a config reload that changes the TTL. */
	public void clear() {
		unsupportedUntil.clear();
	}

	public int size() {
		return unsupportedUntil.size();
	}

	private void sweep() {

		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<String, Long>> it = unsupportedUntil.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<String, Long> entry = it.next();
			if (now >= entry.getValue().longValue())
				it.remove();
		}
	}
}
