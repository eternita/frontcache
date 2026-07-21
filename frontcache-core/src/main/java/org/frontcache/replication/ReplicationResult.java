/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.replication;

/**
 * Immutable snapshot of a replication run.
 * <p>
 * Captures the source/target URLs, counters for transferred/skipped/errored entries,
 * wall-clock duration, and a terminal status string. The console serialises this
 * to JSON for the {@code /cache-replication/status} polling endpoint.
 */
public class ReplicationResult {

	/** Replication completed without errors. */
	public static final String STATUS_COMPLETED = "COMPLETED";
	/** Replication completed but some entries failed. */
	public static final String STATUS_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS";
	/** Replication was cancelled by the operator. */
	public static final String STATUS_CANCELLED = "CANCELLED";
	/** Replication failed (source or target unreachable). */
	public static final String STATUS_FAILED = "FAILED";
	/** Replication is still running. */
	public static final String STATUS_RUNNING = "RUNNING";
	/** No replication has been started. */
	public static final String STATUS_IDLE = "IDLE";

	private String sourceUrl;
	private String targetUrl;
	private long totalSourceKeys;
	private long transferred;
	private long skipped;
	private long errors;
	private long durationMillis;
	private String status;

	public ReplicationResult() {
	}

	public ReplicationResult(String sourceUrl, String targetUrl) {
		this.sourceUrl = sourceUrl;
		this.targetUrl = targetUrl;
		this.status = STATUS_RUNNING;
	}

	// --- Getters / setters (Jackson-friendly) ---

	public String getSourceUrl() {
		return sourceUrl;
	}

	public void setSourceUrl(String sourceUrl) {
		this.sourceUrl = sourceUrl;
	}

	public String getTargetUrl() {
		return targetUrl;
	}

	public void setTargetUrl(String targetUrl) {
		this.targetUrl = targetUrl;
	}

	public long getTotalSourceKeys() {
		return totalSourceKeys;
	}

	public void setTotalSourceKeys(long totalSourceKeys) {
		this.totalSourceKeys = totalSourceKeys;
	}

	public long getTransferred() {
		return transferred;
	}

	public void setTransferred(long transferred) {
		this.transferred = transferred;
	}

	public long getSkipped() {
		return skipped;
	}

	public void setSkipped(long skipped) {
		this.skipped = skipped;
	}

	public long getErrors() {
		return errors;
	}

	public void setErrors(long errors) {
		this.errors = errors;
	}

	public long getDurationMillis() {
		return durationMillis;
	}

	public void setDurationMillis(long durationMillis) {
		this.durationMillis = durationMillis;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	// --- Derived helpers ---

	/** Total entries processed so far (transferred + skipped + errors). */
	public long getProcessed() {
		return transferred + skipped + errors;
	}

	/** Estimated entries remaining (-1 if totalSourceKeys is unknown). */
	public long getRemaining() {
		if (totalSourceKeys <= 0)
			return -1;
		return Math.max(0, totalSourceKeys - getProcessed());
	}

	/** Progress fraction in [0, 1], or -1 if total is unknown. */
	public double getProgressFraction() {
		if (totalSourceKeys <= 0)
			return -1;
		return (double) getProcessed() / totalSourceKeys;
	}

	/** True when the replication is no longer running (completed, cancelled, or failed). */
	public boolean isTerminal() {
		return STATUS_COMPLETED.equals(status)
				|| STATUS_COMPLETED_WITH_ERRORS.equals(status)
				|| STATUS_CANCELLED.equals(status)
				|| STATUS_FAILED.equals(status);
	}

	@Override
	public String toString() {
		return "ReplicationResult{"
				+ "sourceUrl='" + sourceUrl + '\''
				+ ", targetUrl='" + targetUrl + '\''
				+ ", transferred=" + transferred
				+ ", skipped=" + skipped
				+ ", errors=" + errors
				+ ", durationMillis=" + durationMillis
				+ ", status='" + status + '\''
				+ '}';
	}
}
