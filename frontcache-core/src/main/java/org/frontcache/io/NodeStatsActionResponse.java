/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import org.frontcache.cache.CacheStats;
import org.frontcache.core.FcNodeStats;

/**
 * The cheap, pollable view of a node: cache sizes and the JVM they live in.
 *
 * <h2>Why this is not {@code get-cache-state}</h2>
 *
 * {@link CacheStatusActionResponse} carries a {@code Map<String, String>} built for human display, and
 * producing it costs a fresh Lucene {@code DirectoryReader} on every call. That is the right trade for
 * an operator opening a console page and the wrong one for a console that polls every ten seconds
 * forever. This response is typed, and every number behind it is read from state the process already
 * holds - see {@link CacheStats}, which exists for exactly this distinction. {@code get-cache-state}
 * is unchanged and remains the complete view.
 *
 * <h2>Unknown is a value, and it does not add up</h2>
 *
 * Every field can be {@code -1}, meaning this node does not track it - a processor with no on-disk
 * tier has no index size, and a non-HotSpot JVM reports no process CPU. A reader must render that as
 * "unknown" rather than as zero.
 *
 * {@link #getCachedEntries()} follows from that: it is the sum of the two tiers, but only when BOTH
 * are known. A total that quietly means "L2 only, because L1 was unavailable" is worse than no total,
 * because nothing about it looks wrong.
 */
public class NodeStatsActionResponse extends ActionResponse {

	private long cachedEntries = CacheStats.UNKNOWN;
	private long l1Entries = CacheStats.UNKNOWN;
	private long l1Evictions = CacheStats.UNKNOWN;
	private long l1ContentBytes = CacheStats.UNKNOWN;
	private long l2Entries = CacheStats.UNKNOWN;
	private long l2DiskBytes = CacheStats.UNKNOWN;

	private long heapUsedBytes = FcNodeStats.UNKNOWN;
	private long heapMaxBytes = FcNodeStats.UNKNOWN;
	private double cpuProcessLoad = FcNodeStats.UNKNOWN_LOAD;
	private long uptimeMillis = FcNodeStats.UNKNOWN;

	public NodeStatsActionResponse() { // for JSON mapper

	}

	public NodeStatsActionResponse(CacheStats cacheStats, FcNodeStats nodeStats) {
		setAction(FrontcacheAction.GET_NODE_STATS);
		setResponseStatus(RESPONSE_STATUS_OK);

		if (null != cacheStats)
		{
			this.l1Entries = cacheStats.getL1Entries();
			this.l1Evictions = cacheStats.getL1Evictions();
			this.l1ContentBytes = cacheStats.getL1ContentBytes();
			this.l2Entries = cacheStats.getL2Entries();
			this.l2DiskBytes = cacheStats.getL2DiskBytes();
			this.cachedEntries = total(this.l1Entries, this.l2Entries);
		}

		if (null != nodeStats)
		{
			this.heapUsedBytes = nodeStats.getHeapUsedBytes();
			this.heapMaxBytes = nodeStats.getHeapMaxBytes();
			this.cpuProcessLoad = nodeStats.getCpuProcessLoad();
			this.uptimeMillis = nodeStats.getUptimeMillis();
		}
	}

	/**
	 * The tiers are additive - a response is written to L1 or L2 on its cache-level header, never to
	 * both - so the total is a plain sum. It is only a sum when both halves are known, though: see the
	 * class javadoc.
	 */
	private static long total(long l1Entries, long l2Entries) {
		if (CacheStats.UNKNOWN == l1Entries || CacheStats.UNKNOWN == l2Entries)
			return CacheStats.UNKNOWN;

		return l1Entries + l2Entries;
	}

	/** Entries across both tiers, or {@code -1} when either tier does not report. */
	public long getCachedEntries() {
		return cachedEntries;
	}

	public void setCachedEntries(long cachedEntries) {
		this.cachedEntries = cachedEntries;
	}

	public long getL1Entries() {
		return l1Entries;
	}

	public void setL1Entries(long l1Entries) {
		this.l1Entries = l1Entries;
	}

	public long getL1Evictions() {
		return l1Evictions;
	}

	public void setL1Evictions(long l1Evictions) {
		this.l1Evictions = l1Evictions;
	}

	/** Bytes of response CONTENT held in memory - not the tier's retained heap. */
	public long getL1ContentBytes() {
		return l1ContentBytes;
	}

	public void setL1ContentBytes(long l1ContentBytes) {
		this.l1ContentBytes = l1ContentBytes;
	}

	public long getL2Entries() {
		return l2Entries;
	}

	public void setL2Entries(long l2Entries) {
		this.l2Entries = l2Entries;
	}

	public long getL2DiskBytes() {
		return l2DiskBytes;
	}

	public void setL2DiskBytes(long l2DiskBytes) {
		this.l2DiskBytes = l2DiskBytes;
	}

	public long getHeapUsedBytes() {
		return heapUsedBytes;
	}

	public void setHeapUsedBytes(long heapUsedBytes) {
		this.heapUsedBytes = heapUsedBytes;
	}

	public long getHeapMaxBytes() {
		return heapMaxBytes;
	}

	public void setHeapMaxBytes(long heapMaxBytes) {
		this.heapMaxBytes = heapMaxBytes;
	}

	/** 0..1 across all cores, or {@code -1} when the JVM does not report it. */
	public double getCpuProcessLoad() {
		return cpuProcessLoad;
	}

	public void setCpuProcessLoad(double cpuProcessLoad) {
		this.cpuProcessLoad = cpuProcessLoad;
	}

	public long getUptimeMillis() {
		return uptimeMillis;
	}

	public void setUptimeMillis(long uptimeMillis) {
		this.uptimeMillis = uptimeMillis;
	}

}
