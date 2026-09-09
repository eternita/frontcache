/*
 * The cluster merge - docs/circuit-cluster-proposal.md sections 5 and 6.
 *
 * Every case here is one of that proposal's hazards, and each of them is silent when it breaks: the
 * panel keeps rendering a plausible number. The first one is the worst of them - a summed rolling
 * window turns a three-node cluster's traffic into a third of it on the one screen whose purpose is
 * that number.
 *
 * Run: node --test frontcache-console/src/test/js
 *      ./gradlew :frontcache-console:jsTest   (skipped when node is not installed)
 */
const test = require("node:test");
const assert = require("node:assert");

const { internals, warnings } = require("./harness");
const { mergeFrames, canonicalCommand } = internals;

const NOW = 1_700_000_000_000;

/** A frame as FcDashboardJson emits one, with only the fields a case is about. */
function frame(overrides) {
	return Object.assign({
		type: "HystrixCommand",
		name: "Input-Request",
		group: "example.com",
		currentTime: NOW,
		isCircuitBreakerOpen: false,
		errorPercentage: 0,
		errorCount: 0,
		requestCount: 0,
		propertyValue_metricsRollingStatisticalWindowInMilliseconds: 10000,
		propertyValue_executionIsolationStrategy: "THREAD",
		threadPool: "OriginPool"
	}, overrides);
}

function entry(url, overrides, at) {
	return { url: url, frame: frame(overrides), at: at === undefined ? NOW : at };
}

/** What Circuit.update computes from the merged frame, which is what the operator reads. */
function rendered(merged) {
	const windowMs = merged.propertyValue_metricsRollingStatisticalWindowInMilliseconds;
	const clusterRate = merged.requestCount / (windowMs / 1000);

	return {
		hosts: merged.reportingHosts,
		clusterRate: clusterRate,
		hostRate: clusterRate / merged.reportingHosts,
		errorPercentage: Math.round(merged.errorPercentage / merged.reportingHosts * 10) / 10,
		// absent renders as a dash, so it is read as absent here too
		latencyMean: merged.latencyExecute_mean === undefined
				? null : Math.floor(merged.latencyExecute_mean / merged.reportingHosts),
		p99: merged.latencyExecute === undefined
				? null : Math.floor(merged.latencyExecute["99"] / merged.reportingHosts)
	};
}

// ---- the divisor -------------------------------------------------------------------------------

test("the rolling window is not summed, so the cluster rate is the sum of the members' rates", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { requestCount: 217 }),
		entry("https://fc-eu/", { requestCount: 179 }),
		entry("https://fc-ap/", { requestCount: 174 })
	], NOW);

	assert.strictEqual(merged.propertyValue_metricsRollingStatisticalWindowInMilliseconds, 10000);
	assert.strictEqual(merged.requestCount, 570);

	const view = rendered(merged);
	assert.strictEqual(view.hosts, 3);
	assert.strictEqual(view.clusterRate, 57);
	assert.strictEqual(view.hostRate, 19);
});

test("members whose rolling windows disagree are normalized to the widest, and warned about", () => {

	// 100 in 10 s = 10/s, 400 in 20 s = 20/s. The cluster is 30/s however the window is reported.
	const merged = mergeFrames([
		entry("https://fc-edge/", { requestCount: 100, propertyValue_metricsRollingStatisticalWindowInMilliseconds: 10000 }),
		entry("https://fc-eu/", { requestCount: 400, propertyValue_metricsRollingStatisticalWindowInMilliseconds: 20000 })
	], NOW);

	assert.strictEqual(merged.propertyValue_metricsRollingStatisticalWindowInMilliseconds, 20000);
	assert.strictEqual(rendered(merged).clusterRate, 30);

	assert.ok(warnings.some((line) => line.includes("rollingStats")),
			"a fleet whose windows differ is a config inconsistency worth one line: " + warnings.join(" | "));
});

// ---- the circuit state -------------------------------------------------------------------------

test("isCircuitBreakerOpen is a COUNT of open members, which is what renders 'Circuit 1 of 3 Open'", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { isCircuitBreakerOpen: true }),
		entry("https://fc-eu/", { isCircuitBreakerOpen: false }),
		entry("https://fc-ap/", { isCircuitBreakerOpen: false })
	], NOW);

	// Never `true`: circuitStatus tests `=== true` BEFORE comparing to reportingHosts, so a boolean
	// here would read as the whole cluster being open on one tripped member.
	assert.strictEqual(merged.isCircuitBreakerOpen, 1);
	assert.strictEqual(merged.reportingHosts, 3);

	assert.strictEqual(mergeFrames([
		entry("https://fc-edge/", { isCircuitBreakerOpen: true }),
		entry("https://fc-eu/", { isCircuitBreakerOpen: true })
	], NOW).isCircuitBreakerOpen, 2);

	assert.strictEqual(mergeFrames([
		entry("https://fc-edge/", {}),
		entry("https://fc-eu/", {})
	], NOW).isCircuitBreakerOpen, 0);
});

// ---- reporting hosts ---------------------------------------------------------------------------

test("reportingHosts counts the members reporting THIS command, not the cluster's size", () => {

	// Cache-Bypass on a node that has never bypassed the cache emits no frame at all
	// (FcCommandMetrics instances are created on first execution), which is what the screenshot this
	// was built from shows. Dividing by 3 there would understate the per-host rate by the idle node.
	const merged = mergeFrames([
		entry("https://fc-edge/", { name: "Cache-Bypass", requestCount: 60 }),
		entry("https://fc-eu/", { name: "Cache-Bypass", requestCount: 40 })
	], NOW);

	assert.strictEqual(merged.reportingHosts, 2);
	assert.strictEqual(rendered(merged).hostRate, 5);
});

test("a member that went silent is dropped, so the cluster total falls instead of freezing", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { requestCount: 100 }),
		entry("https://fc-eu/", { requestCount: 100 }),
		entry("https://fc-ap/", { requestCount: 100 }, NOW - 6000)
	], NOW);

	assert.strictEqual(merged.reportingHosts, 2);
	assert.strictEqual(merged.requestCount, 200);
});

test("every member stale means no card at all, not a card of zeroes", () => {

	assert.strictEqual(mergeFrames([
		entry("https://fc-edge/", { requestCount: 100 }, NOW - 6000),
		entry("https://fc-eu/", { requestCount: 100 }, NOW - 60000)
	], NOW), null);
});

// ---- mixed fleet -------------------------------------------------------------------------------

test("a legacy key and the key that replaced it merge into ONE card", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { name: "Input-Request", requestCount: 100 }),
		entry("https://fc-eu/", { name: "Input-Requests", requestCount: 100 })
	], NOW);

	assert.strictEqual(merged.name, "Input-Request");
	assert.strictEqual(merged.reportingHosts, 2);
	assert.strictEqual(merged.requestCount, 200);
});

test("every pre-2.9.0 command key maps onto the key that replaced it", () => {

	assert.strictEqual(canonicalCommand("Input-Requests"), "Input-Request");
	assert.strictEqual(canonicalCommand("Include-Requests"), "Include-Request");
	assert.strictEqual(canonicalCommand("Cache-Hits"), "Cache-Hit");
	assert.strictEqual(canonicalCommand("Cache-Origin-http"), "Cache-Miss");
	assert.strictEqual(canonicalCommand("Cache-Origin-filter"), "Cache-Miss");
	assert.strictEqual(canonicalCommand("Origin-Hits"), "Cache-Bypass");

	// anything else is its own canonical name - a custom command, or one from a newer node
	assert.strictEqual(canonicalCommand("Cache-Miss"), "Cache-Miss");
	assert.strictEqual(canonicalCommand("Some-Custom-Command"), "Some-Custom-Command");
});

test("Cache-Origin-http + Cache-Origin-filter from ONE member is one host, not two", () => {

	// A pre-2.9.0 filter-mode node has both live at once: the filter chain serves the page's own
	// origin call and HTTP serves its includes. Counting frames instead of members here would halve
	// that node's latency and double its per-host rate.
	const merged = mergeFrames([
		{ url: "https://fc-filter/", frame: frame({ name: "Cache-Origin-http", requestCount: 30, latencyExecute_mean: 200, latencyExecute: { "99": 400 } }), at: NOW },
		{ url: "https://fc-filter/", frame: frame({ name: "Cache-Origin-filter", requestCount: 30, latencyExecute_mean: 100, latencyExecute: { "99": 200 } }), at: NOW }
	], NOW);

	assert.strictEqual(merged.name, "Cache-Miss");
	assert.strictEqual(merged.reportingHosts, 1);
	assert.strictEqual(merged.requestCount, 60);

	// averaged within the member, not summed: 150, not 300
	assert.strictEqual(merged.latencyExecute_mean, 150);
	assert.strictEqual(merged.latencyExecute["99"], 300);
});

// ---- latency -----------------------------------------------------------------------------------

test("latency is summed across members BECAUSE the renderer divides it by reportingHosts", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { requestCount: 10, latencyExecute_mean: 100, latencyExecute: { "99": 300 } }),
		entry("https://fc-eu/", { requestCount: 10, latencyExecute_mean: 200, latencyExecute: { "99": 500 } })
	], NOW);

	assert.strictEqual(merged.latencyExecute_mean, 300);

	const view = rendered(merged);
	assert.strictEqual(view.latencyMean, 150);
	assert.strictEqual(view.p99, 400);
});

// ---- error percentage --------------------------------------------------------------------------

test("the error percentage is weighted by traffic, not an average of the members' percentages", () => {

	// One node taking 1000 clean requests, one taking 10 that all failed. The cluster's error rate is
	// 10/1010 = 1%, not the 50% an unweighted mean of 0% and 100% would put on the card.
	const merged = mergeFrames([
		entry("https://fc-edge/", { requestCount: 1000, errorCount: 0, errorPercentage: 0 }),
		entry("https://fc-eu/", { requestCount: 10, errorCount: 10, errorPercentage: 100 })
	], NOW);

	assert.strictEqual(rendered(merged).errorPercentage, 1);
});

test("a frame that reports a percentage without the counts behind it still contributes", () => {

	const merged = mergeFrames([
		{ url: "https://a/", frame: { name: "Input-Request", errorPercentage: 10 }, at: NOW },
		{ url: "https://b/", frame: { name: "Input-Request", errorPercentage: 30 }, at: NOW }
	], NOW);

	assert.strictEqual(merged.errorPercentage / merged.reportingHosts, 20);
});

// ---- absent is not zero ------------------------------------------------------------------------

test("a counter no member reports stays absent, so the card shows a dash rather than a zero", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { requestCount: 5 }),
		entry("https://fc-eu/", { requestCount: 5 })
	], NOW);

	assert.strictEqual(merged.rollingCountTimeout, undefined);
	assert.strictEqual(merged.latencyExecute, undefined);
});

test("a counter some members report is summed, with a silent member counting as zero", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { rollingCountTimeout: 3, rollingCountSuccess: 100 }),
		entry("https://fc-eu/", { rollingCountSuccess: 100 })
	], NOW);

	assert.strictEqual(merged.rollingCountTimeout, 3);
	assert.strictEqual(merged.rollingCountSuccess, 200);
});

// ---- property values ---------------------------------------------------------------------------

test("a threshold the members disagree on is the maximum, never a sum, and is warned about", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { propertyValue_executionTimeoutInMilliseconds: 1000 }),
		entry("https://fc-eu/", { propertyValue_executionTimeoutInMilliseconds: 20000 })
	], NOW);

	assert.strictEqual(merged.propertyValue_executionTimeoutInMilliseconds, 20000);
	assert.ok(warnings.some((line) => line.includes("propertyValue_executionTimeoutInMilliseconds")),
			"a per-node config difference across one cluster is real information: " + warnings.join(" | "));
});

test("THREAD wins the isolation strategy, because it is the one with a rejection count behind it", () => {

	const merged = mergeFrames([
		entry("https://fc-edge/", { propertyValue_executionIsolationStrategy: "SEMAPHORE" }),
		entry("https://fc-eu/", { propertyValue_executionIsolationStrategy: "THREAD" })
	], NOW);

	assert.strictEqual(merged.propertyValue_executionIsolationStrategy, "THREAD");
});

test("the merged frame is still a HystrixCommand frame the renderer accepts", () => {

	const merged = mergeFrames([entry("https://fc-edge/", { requestCount: 1 }), entry("https://fc-eu/", {})], NOW);

	assert.strictEqual(merged.type, "HystrixCommand");
	assert.strictEqual(merged.name, "Input-Request");
	assert.strictEqual(merged.group, "example.com");
	assert.strictEqual(merged.threadPool, "OriginPool");
});
