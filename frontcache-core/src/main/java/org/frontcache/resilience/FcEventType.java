/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

/**
 * Execution outcomes of an {@link FcCommand}, replacing {@code com.netflix.hystrix.HystrixEventType}.
 *
 * The first ten constants are one-for-one with the Hystrix event types Frontcache can produce, and
 * their rolling counts feed the {@code rollingCount*} fields of the dashboard JSON - so the set is
 * fixed by that wire contract, not by what the code happens to need. {@code COLLAPSED} and
 * {@code RESPONSE_FROM_CACHE} are here for exactly that reason: Frontcache never emits them (it
 * registers no collapsers and does not use request caching), but the dashboard asserts their fields
 * exist, so the emitter needs a counter to read a zero from.
 *
 * See docs/hystrix-to-resilience4j-proposal.md sections 4.1 and 7.3.
 */
public enum FcEventType {

	SUCCESS,
	FAILURE,
	TIMEOUT,
	BAD_REQUEST,
	SHORT_CIRCUITED,
	THREAD_POOL_REJECTED,
	SEMAPHORE_REJECTED,
	FALLBACK_SUCCESS,
	FALLBACK_FAILURE,

	/**
	 * Never emitted. The fallback semaphore is parsed and reported but deliberately not enforced -
	 * every call that reaches a fallback already holds an Input-Requests permit, so this state is
	 * unreachable. Kept so {@code rollingCountFallbackRejection} has a counter to read 0 from.
	 * See proposal section 6.3.
	 */
	FALLBACK_REJECTION,

	/** The command threw out of {@code execute()} instead of returning a fallback. */
	EXCEPTION_THROWN,

	/** Never emitted - Frontcache does not use Hystrix request caching. */
	RESPONSE_FROM_CACHE,

	/** Never emitted - Frontcache registers no collapsers. */
	COLLAPSED,

	/**
	 * NOT a Hystrix event, and deliberately so.
	 *
	 * A semaphore-isolated command that ran past its configured timeout budget. It is observed and
	 * reported by the watchdog ({@link FcCommandMetrics#sweepOverruns}) and nothing else: it does not
	 * abort the call, is not mapped by {@link FallbackLogger#failureType}, and must never be counted
	 * as a circuit-breaker failure or folded into {@code rollingCountTimeout} - Hystrix treats a
	 * timeout as a failure, so doing that would move errorPercentage and could open a circuit on a
	 * request that succeeded. It surfaces as an additional JSON field, which is safe because the
	 * dashboard asserts key presence and ignores extras.
	 *
	 * See proposal section 6.2.
	 */
	BUDGET_OVERRUN_OBSERVED;

	/** True for the outcomes that mean "the origin/cache call itself did not complete normally". */
	public boolean isFailureOutcome()
	{
		return this == FAILURE || this == TIMEOUT || this == SHORT_CIRCUITED
				|| this == THREAD_POOL_REJECTED || this == SEMAPHORE_REJECTED;
	}
}
