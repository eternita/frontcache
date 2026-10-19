/*
 * Copyright 2015 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.replication;

/**
 * Callback interface for receiving progress updates during cache replication.
 * <p>
 * Implementations are notified as entries are transferred from the source edge
 * to the target edge. The console UI uses this to display a live progress bar.
 */
public interface ReplicationProgressListener {

	/**
	 * Called after each entry is processed (transferred, skipped, or failed).
	 *
	 * @param transferred   number of entries successfully written to the target
	 * @param skipped       number of entries already present on the target
	 * @param errors        number of entries that failed to transfer
	 * @param totalRemaining estimated number of entries still to process (may be -1 if unknown)
	 */
	void onProgress(long transferred, long skipped, long errors, long totalRemaining);

	/**
	 * Called when replication completes (successfully, with errors, or cancelled).
	 *
	 * @param result the final replication result
	 */
	void onComplete(ReplicationResult result);

	/**
	 * Called when a single entry fails to transfer.
	 *
	 * @param key the cache key that failed
	 * @param e   the exception that caused the failure
	 */
	void onError(String key, Exception e);

}
