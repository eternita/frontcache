/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.util.Properties;

/**
 * Bridges the package-private test seams of {@code org.frontcache.resilience} to tests in other packages.
 *
 * The registries, the metrics instances and the config singleton are all JVM-global by design - Hystrix's
 * were too - so a test that touches them has to reset them, and a test in {@code org.frontcache.metrics}
 * cannot reach the package-private seams that do it. Widening those to public would put four
 * test-only methods on the production API to save this one class, so this exists instead.
 *
 * Test-scope only: it is not compiled into the jar.
 */
public class FcTestSupport {

	private FcTestSupport() { }

	/** Everything JVM-global the resilience layer owns, back to its initial state. */
	public static void resetAll()
	{
		FcResilienceRegistry.reset();
		FcCommandMetrics.reset();
		FcThreadPools.shutdownAll();
		FcResilienceConfig.reset();
	}

	public static void installConfig(Properties properties)
	{
		FcResilienceConfig.install(properties);
	}

	/** Advances one command's rolling window by a single bucket. */
	public static void rotate(FcCommandMetrics metrics)
	{
		metrics.rotate();
	}
}
