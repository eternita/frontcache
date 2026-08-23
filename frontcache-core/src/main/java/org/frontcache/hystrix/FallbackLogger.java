/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 *
 * Marker for Logger
 *
 */
public class FallbackLogger {

	public static final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSZ");

	/**
	 * Maps command execution events to the failure category shown on the realtime dashboard
	 * (Short-Circuited | Bad Request | Timeout | Rejected | Failure). Used to tag failed
	 * requests in the dedicated failed-requests log.
	 *
	 * The returned strings are deliberately unchanged by the Resilience4j migration: they land in the
	 * failed-requests log and in {@code RequestLogger}'s positional output, which log tooling parses.
	 * Only the parameter type moved from {@code List<HystrixEventType>} to {@code List<FcEventType>},
	 * and {@link FcEventType} keeps the same constant names so the mapping is one-for-one.
	 *
	 * @param events the command's execution events (e.g. from getExecutionEvents())
	 * @return a lower-case category label, or "error" when none of the known events are present
	 */
	public static String failureType(List<FcEventType> events) {
		if (null == events)
			return "error";

		if (events.contains(FcEventType.SHORT_CIRCUITED))
			return "short-circuited";
		if (events.contains(FcEventType.TIMEOUT))
			return "timeout";
		if (events.contains(FcEventType.THREAD_POOL_REJECTED) || events.contains(FcEventType.SEMAPHORE_REJECTED))
			return "rejected";
		if (events.contains(FcEventType.BAD_REQUEST))
			return "bad-request";
		if (events.contains(FcEventType.FAILURE))
			return "failure";

		return "error";
	}

}
