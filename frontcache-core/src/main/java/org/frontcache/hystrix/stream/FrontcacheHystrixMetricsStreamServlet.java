/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix.stream;

import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.hystrix.FcResilienceConfig;

public class FrontcacheHystrixMetricsStreamServlet extends FrontcacheHystrixSampleSseServlet {

    private static final long serialVersionUID = -7548505095303313237L;

    /* used to track number of connections and throttle */
    private static AtomicInteger concurrentConnections = new AtomicInteger(0);

    public FrontcacheHystrixMetricsStreamServlet() {
        super(DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS);
    }

    /**
     * Same key, same file, no Archaius. This used to be an Archaius {@code DynamicIntProperty}, which is
     * exactly the coupling that made {@code FrontcacheConfigBootstrapListener} necessary: touching this
     * servlet's static initializer before any page request installed Archaius first and silently dropped
     * every per-command override in hystrix.properties. Read from a plain Properties instead, per
     * request, so nothing depends on class-initialization order.
     */
    @Override
    protected int getMaxNumberConcurrentConnectionsAllowed() {
        return FcResilienceConfig.getInstance().streamMaxConcurrentConnections();
    }

    @Override
    protected int getNumberCurrentConnections() {
        return concurrentConnections.get();
    }

    @Override
    protected int incrementAndGetCurrentConcurrentConnections() {
        return concurrentConnections.incrementAndGet();
    }

    @Override
    protected void decrementCurrentConcurrentConnections() {
        concurrentConnections.decrementAndGet();
    }
}
