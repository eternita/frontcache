/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache;

/**
 * A cheap size-and-churn snapshot of a cache processor, for the metrics exporter.
 *
 * Deliberately NOT {@code getCacheStatus()}, which the management API already returns: that one is a
 * {@code Map<String, String>} built for human display, and reading it costs a fresh Lucene
 * {@code DirectoryReader} every time. Fine for an operator clicking a console page; wrong for a
 * Prometheus scrape arriving every few seconds forever. Everything here is read from state the process
 * already holds.
 *
 * <h2>Unknown is a value</h2>
 *
 * Every field can be {@link #UNKNOWN}, and the exporter registers no series for one that is. A cache
 * processor with no on-disk tier does not have an "index size of 0" - it has no index, and a gauge
 * reading a flat zero would be a lie an alert could be built on.
 *
 * <h2>Micrometer-free on purpose</h2>
 *
 * The cache package does not depend on the metrics package. Export is optional and off by default;
 * having the cache implementations register meters directly would put an optional dependency on the
 * request path's most central class. So the cache side exposes plain numbers and
 * {@code org.frontcache.metrics} stays the only thing that knows what a meter is.
 */
public class CacheStats {

	/** The processor does not track this number. Distinct from zero. */
	public static final long UNKNOWN = -1;

	public static final CacheStats NOT_TRACKED = new CacheStats(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);

	private final long l1Entries;
	private final long l1Evictions;
	private final long l1ContentBytes;
	private final long l2Entries;
	private final long l2DiskBytes;

	/**
	 * Kept for cache processors that do not track L1's byte size - {@code CacheProcessor} is a
	 * reflection-loaded extension point, so an implementation outside this repo still compiles and
	 * still reports honestly: not tracking a number is what {@link #UNKNOWN} is for, and unlike the
	 * {@code FallbackResolver} case a default here cannot silently answer a question wrongly.
	 */
	public CacheStats(long l1Entries, long l1Evictions, long l2Entries, long l2DiskBytes) {
		this(l1Entries, l1Evictions, UNKNOWN, l2Entries, l2DiskBytes);
	}

	public CacheStats(long l1Entries, long l1Evictions, long l1ContentBytes, long l2Entries, long l2DiskBytes) {
		this.l1Entries = l1Entries;
		this.l1Evictions = l1Evictions;
		this.l1ContentBytes = l1ContentBytes;
		this.l2Entries = l2Entries;
		this.l2DiskBytes = l2DiskBytes;
	}

	/** Entries in the in-memory tier, or {@link #UNKNOWN}. */
	public long getL1Entries() {
		return l1Entries;
	}

	/**
	 * Entries evicted from the in-memory tier since JVM start, or {@link #UNKNOWN}.
	 *
	 * A monotonic total, not a rate: Prometheus derives the rate, and it is the rate that says whether
	 * L1 is too small for the working set.
	 */
	public long getL1Evictions() {
		return l1Evictions;
	}

	/**
	 * Bytes of response CONTENT held in the in-memory tier, or {@link #UNKNOWN}.
	 *
	 * Not the tier's retained heap: header maps, key strings and per-object overhead are not in it. The
	 * distinction matters wherever this is shown next to a JVM heap figure. Maintained as the cache
	 * changes rather than measured on demand - see {@code L1ByteCounter}.
	 */
	public long getL1ContentBytes() {
		return l1ContentBytes;
	}

	/** Documents in the on-disk tier, or {@link #UNKNOWN}. */
	public long getL2Entries() {
		return l2Entries;
	}

	/**
	 * Bytes the on-disk tier occupies, or {@link #UNKNOWN}. Answers "will the cache volume fill up",
	 * which the entry count does not - entry sizes on a page cache vary by orders of magnitude.
	 */
	public long getL2DiskBytes() {
		return l2DiskBytes;
	}

	public boolean isTracked(long value) {
		return UNKNOWN != value;
	}

	@Override
	public String toString() {
		return "CacheStats{l1Entries=" + l1Entries + ", l1Evictions=" + l1Evictions
				+ ", l1ContentBytes=" + l1ContentBytes
				+ ", l2Entries=" + l2Entries + ", l2DiskBytes=" + l2DiskBytes + "}";
	}

}
