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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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

	/**
	 * How many entries are transferred at once.
	 *
	 * Kept at or below the per-route connection limit the clients are built with
	 * (FrontCacheClient.CONNECTIONS_MAX_PER_ROUTE) - all traffic here goes to two nodes, i.e. two
	 * routes, so a larger number would only queue on the connection pool instead.
	 */
	static final int DEFAULT_WORKER_COUNT = 8;

	private int workerCount = DEFAULT_WORKER_COUNT;

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
	 * Overrides how many entries are transferred concurrently. Values below 1 are clamped to 1, which
	 * restores the pre-2.9.0 strictly-sequential behaviour.
	 */
	public void setWorkerCount(int workerCount) {
		this.workerCount = Math.max(1, workerCount);
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

		// Keys already on the target cost nothing to resolve - no I/O - so they are counted here rather
		// than occupying a worker.
		long skipped = 0;
		List<String> toTransfer = new ArrayList<String>();
		for (String rawKey : sourceKeys) {
			String key = rawKey.trim();
			if (key.isEmpty())
				continue;

			if (targetKeys.contains(key))
				skipped++;
			else
				toTransfer.add(key);
		}
		result.setSkipped(skipped);

		transferInParallel(toTransfer, skipped, totalKeys, listener);
	}

	/**
	 * Transfers the missing entries concurrently.
	 *
	 * Each entry is two synchronous HTTP round trips - a get from the source, a put to the target -
	 * and they used to run strictly one after another. At a realistic 5ms per round trip a
	 * 100 000-entry cache is about 17 minutes of pure latency, essentially all of it waiting.
	 *
	 * <b>Completions are consumed on THIS thread</b>, through an {@link ExecutorCompletionService}.
	 * That is deliberate: {@link ReplicationResult}'s setters are plain, and a
	 * {@link ReplicationProgressListener} is an interface the console implements, so neither can be
	 * assumed thread safe. Only the two HTTP calls move to worker threads; every counter update and
	 * every listener callback still happens on one thread, exactly as before.
	 */
	private void transferInParallel(List<String> keys, long skipped, int totalKeys,
			ReplicationProgressListener listener) {

		long transferred = 0;
		long errors = 0;

		if (keys.isEmpty()) {
			if (null != listener)
				listener.onProgress(transferred, skipped, errors, totalKeys - skipped);
			return;
		}

		ExecutorService pool = Executors.newFixedThreadPool(Math.min(workerCount, keys.size()), new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "CacheReplicator-worker");
				t.setDaemon(true); // replication must never hold up JVM shutdown
				return t;
			}
		});

		try {
			CompletionService<TransferOutcome> completions = new ExecutorCompletionService<TransferOutcome>(pool);

			int submitted = 0;
			for (final String key : keys) {
				if (cancelled)
					break;

				completions.submit(new Callable<TransferOutcome>() {
					@Override
					public TransferOutcome call() {
						// checked again here: a task queued before the cancel should not still do its I/O
						if (cancelled)
							return null;
						return new TransferOutcome(key, transferEntry(key));
					}
				});
				submitted++;
			}

			for (int completed = 0; completed < submitted; completed++) {
				TransferOutcome outcome;
				try {
					outcome = completions.take().get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					logger.info("Replication interrupted at {}/{} entries", transferred + skipped + errors, totalKeys);
					break;
				} catch (ExecutionException e) {
					// transferEntry() catches everything itself, so this is a defect rather than a
					// transfer failure - count it as an error and carry on with the rest
					logger.error("Replication task failed unexpectedly", e.getCause());
					errors++;
					continue;
				}

				if (null == outcome)
					continue; // task saw the cancel flag and did nothing

				if (outcome.ok) {
					transferred++;
				} else {
					errors++;
					if (listener != null) {
						// best-effort: we don't have the original exception here,
						// but the error is logged in transferEntry
						listener.onError(outcome.key, new RuntimeException("putToCache failed for key: " + outcome.key));
					}
				}

				// Update result counters and notify listener
				result.setTransferred(transferred);
				result.setErrors(errors);

				if (listener != null) {
					listener.onProgress(transferred, skipped, errors, totalKeys - transferred - skipped - errors);
				}

				if (cancelled) {
					logger.info("Replication cancelled by operator at {}/{} entries",
							transferred + skipped + errors, totalKeys);
					break;
				}
			}
		} finally {
			// shutdownNow: on cancel there may be queued tasks that must not start, and in-flight
			// ones are bounded by the client's socket timeout
			pool.shutdownNow();
		}
	}

	/** What one transfer did, carried back to the consuming thread. */
	private static final class TransferOutcome {
		final String key;
		final boolean ok;

		TransferOutcome(String key, boolean ok) {
			this.key = key;
			this.ok = ok;
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
