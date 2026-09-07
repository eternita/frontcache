/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies an edited conf/resilience.properties to a running node, for the
 * {@code reload-resilience} management action.
 *
 * <h2>Why this needs a class of its own</h2>
 *
 * The file is read once, into a {@code volatile} singleton, and then <b>three</b> caches are built
 * from it and never revisited: {@link FcResilienceConfig#getInstance()}, the per-command-key
 * Resilience4j objects in {@link FcResilienceRegistry}, and the per-pool-key executors in
 * {@link FcThreadPools}. Re-reading the file alone changes nothing a request can observe, which is
 * the trap this class exists to close - a "reload" that quietly applies none of the settings is
 * worse than no reload at all.
 *
 * <h2>What applies immediately, and what cannot</h2>
 *
 * Command settings - timeouts, breaker thresholds, sleep windows, bulkhead permits, the
 * {@code COMMAND_KEY_INHERITANCE} chain, the legacy {@code hystrix.*} prefixes - all come back
 * through the normal path, because dropping the cached objects means the next command builds its
 * config from scratch.
 *
 * Two things do not, and both are reported in {@link Result#warnings()} rather than left to be
 * inferred: dropping the cached breakers <b>loses circuit state and the rolling windows</b> the
 * dashboard reads, and a changed {@code maxQueueSize} selects a queue TYPE that a running pool
 * cannot be given (see {@link FcThreadPools#reapplyGeometry}).
 */
public class FcResilienceReload {

	private static final Logger logger = LoggerFactory.getLogger(FcResilienceReload.class);

	private FcResilienceReload() { }

	/**
	 * @param loadedFrom the file the settings came from, for the operator's benefit
	 * @param warnings   what could not be applied, or what applying cost
	 */
	public record Result(String loadedFrom, List<String> warnings) { }

	public static Result reload()
	{
		FcResilienceConfig config = FcResilienceConfig.reload();

		List<String> warnings = new ArrayList<String>();

		// Before the pools: a pool's geometry is read off the config, and there is no reason for the
		// two halves to see different instances.
		warnings.addAll(FcThreadPools.reapplyGeometry(config));

		FcResilienceRegistry.invalidateAll();
		warnings.add("circuit-breaker state and the rolling metric windows were reset - an open circuit"
				+ " is now closed, and the realtime page will show a step change");

		logger.info("conf/resilience.properties reloaded from " + config.loadedFrom()
				+ ", " + warnings.size() + " note(s)");

		return new Result(config.loadedFrom(), Collections.unmodifiableList(warnings));
	}
}
