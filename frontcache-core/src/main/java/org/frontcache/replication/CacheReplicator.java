/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.replication;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replicates cache entries from a source edge to a target edge.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Fetch the target's key set into memory (O(target size) memory).</li>
 *   <li>Stream the source's keys. For each key not present on the target,
 *       fetch the full {@link WebResponse} from the source and push it to the target.</li>
 *   <li>Entries already present on the target are skipped (idempotent).</li>
 * </ol>
 * <p>
 * The replicator is designed to run in a background thread. Progress is reported
 * via a {@link ReplicationProgressListener}. Cancellation is supported via
 * {@link #cancel()} — the next iteration checks the flag and stops cleanly.
 * <p>
 * Thread-safety: a single replicator instance must not be shared across threads.
 * The {@code cancelled} flag is volatile so a cancelling thread is visible to the
 * replication thread without explicit synchronisation.
 */
public class CacheReplicator {

	private static final Logger logger = LoggerFactory.getLogger(CacheReplicator.class);

	private final FrontCacheClient source;
	private final FrontCacheClient target;

	private volatile boolean cancelled = false;
	private volatile boolean running = false;

	private final ReplicationResult result;

	/**
	 * @param source the edge to read entries from
	 * @param target the edge to write entries to
	 */
	public CacheReplicator(FrontCacheClient source, FrontCacheClient target) {
		this.source = source;
		this.target = target;
		this.result = new ReplicationResult(source.getFrontCacheURL(), target.getFrontCacheURL());
	}

	/**
	 * Runs the replication. Blocks until completion, cancellation, or failure.
	 * <p>
	 * Safe to call only once per instance. Subsequent calls return immediately
	 * with the previous result.
	 *
	 * @param listener optional progress listener; may be {@code null}
	 * @return the final {@link ReplicationResult}
	 */
	public ReplicationResult run(ReplicationProgressListener listener) {
		if (running) {
			logger.warn("Replication already running from {} to {}", result.getSourceUrl(), result.getTargetUrl());
			return result;
		}

		running = true;
		cancelled = false;
		long startNanos = System.nanoTime();

		try {
			logger.info("Starting replication: {} -> {}", result.getSourceUrl(), result.getTargetUrl());

			// Step 1: load target key set
			Set<String> targetKeys = fetchTargetKeys();
			if (targetKeys == null) {
				result.setStatus(ReplicationResult.STATUS_FAILED);
				result.setDurationMillis(elapsedMillis(startNanos));
				logger.error("Replication failed: could not fetch target keys from {}", result.getTargetUrl());
				return result;
			}
			logger.info("Target has {} cached keys", targetKeys.size());

			// Step 2: stream source keys and transfer missing entries
			replicateEntries(targetKeys, listener);

			// Step 3: set terminal status (only if not already set to a terminal state by step 2)
			if (!result.isTerminal()) {
				if (cancelled) {
					result.setStatus(ReplicationResult.STATUS_CANCELLED);
				} else if (result.getErrors() > 0) {
					result.setStatus(ReplicationResult.STATUS_COMPLETED_WITH_ERRORS);
				} else {
					result.setStatus(ReplicationResult.STATUS_COMPLETED);
				}
			}

		} catch (Exception e) {
			logger.error("Replication failed unexpectedly", e);
			result.setStatus(ReplicationResult.STATUS_FAILED);
		} finally {
			result.setDurationMillis(elapsedMillis(startNanos));
			running = false;
			logger.info("Replication finished: {}", result);
		}

		if (listener != null) {
			listener.onComplete(result);
		}

		return result;
	}

	/**
	 * Fetches the target edge's cached key set.
	 *
	 * @return set of keys, or {@code null} on failure
	 */
	private Set<String> fetchTargetKeys() {
		try {
			return target.getCachedKeysSet();
		} catch (Exception e) {
			logger.error("Failed to fetch keys from target {}", result.getTargetUrl(), e);
			return null;
		}
	}

	/**
	 * Streams source keys, computes diff against targetKeys, and transfers missing entries.
	 */
	private void replicateEntries(Set<String> targetKeys, ReplicationProgressListener listener) {
		ByteArrayOutputStream keyStream = new ByteArrayOutputStream();

		boolean keysOk = source.getCachedKeys(keyStream);
		if (!keysOk) {
			logger.error("Failed to fetch keys from source {}", result.getSourceUrl());
			result.setStatus(ReplicationResult.STATUS_FAILED);
			return;
		}

		String[] sourceKeys;
		try {
			sourceKeys = keyStream.toString("UTF-8").split("\n");
		} catch (UnsupportedEncodingException e) {
			logger.error("Failed to decode source keys", e);
			result.setStatus(ReplicationResult.STATUS_FAILED);
			return;
		}

		// Filter out empty lines and count total
		int totalKeys = 0;
		for (String k : sourceKeys) {
			if (!k.trim().isEmpty())
				totalKeys++;
		}
		result.setTotalSourceKeys(totalKeys);
		logger.info("Source has {} cached keys, diff = {} entries to transfer",
				totalKeys, totalKeys - (int) targetKeys.size());

		long transferred = 0;
		long skipped = 0;
		long errors = 0;

		for (String rawKey : sourceKeys) {
			if (cancelled) {
				logger.info("Replication cancelled by operator at {}/{} entries", transferred + skipped + errors, totalKeys);
				break;
			}

			String key = rawKey.trim();
			if (key.isEmpty())
				continue;

			if (targetKeys.contains(key)) {
				skipped++;
			} else {
				boolean ok = transferEntry(key);
				if (ok) {
					transferred++;
				} else {
					errors++;
					if (listener != null) {
						// best-effort: we don't have the original exception here,
						// but the error is logged in transferEntry
						listener.onError(key, new RuntimeException("putToCache failed for key: " + key));
					}
				}
			}

			// Update result counters and notify listener
			result.setTransferred(transferred);
			result.setSkipped(skipped);
			result.setErrors(errors);

			if (listener != null) {
				listener.onProgress(transferred, skipped, errors, totalKeys - transferred - skipped - errors);
			}
		}
	}

	/**
	 * Fetches a single entry from the source and pushes it to the target.
	 *
	 * @return true if the entry was successfully stored on the target
	 */
	private boolean transferEntry(String key) {
		try {
			WebResponse entry = source.getFromCache(key);
			if (entry == null) {
				logger.warn("Entry vanished from source between key-list and fetch: key={}", key);
				return false;
			}

			boolean ok = target.putToCache(key, entry);
			if (!ok) {
				logger.warn("putToCache returned failure for key={} on target {}", key, result.getTargetUrl());
			}
			return ok;

		} catch (Exception e) {
			logger.error("Failed to transfer entry: key={}", key, e);
			return false;
		}
	}

	private static long elapsedMillis(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	// --- Accessors ---

	/** Returns the current result snapshot (safe to call while running). */
	public ReplicationResult getCurrentResult() {
		return result;
	}

	/** True if a replication run is currently in progress. */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Requests cancellation of the running replication.
	 * The replicator checks this flag between entries and stops at the next opportunity.
	 * No-op if no replication is running.
	 */
	public void cancel() {
		if (running) {
			logger.info("Cancellation requested for replication {} -> {}", result.getSourceUrl(), result.getTargetUrl());
			cancelled = true;
		}
	}
}
