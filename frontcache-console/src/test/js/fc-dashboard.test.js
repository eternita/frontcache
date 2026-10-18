/*
 * fc-dashboard.js as a console with NO plugin renders it - the Community console of
 * docs/ip-protection-apache-core-and-prop-pro-proposal.md. The cluster arithmetic is the Pro plugin's
 * (fc-dashboard-cluster.js, tested in fc-dashboard-cluster.test.js); without it a stream that names a
 * cluster - a config the server would not even produce without the plugin - must still render as an
 * unclustered one, never as a broken one.
 *
 * Run: ./gradlew :frontcache-console:jsTest   (node --test; skipped when node is not installed)
 */
const test = require("node:test");
const assert = require("node:assert");

const { internals, FcDashboard } = require("./harness");

const STREAMS = [
	{ name: "us", url: "https://fc-edge.example.com:443/", cluster: "Edge layer" },
	{ name: "eu", url: "https://fc-eu.example.com:443/", cluster: "Edge layer" },
	{ name: "solo", url: "http://filter.example.com:8082/", cluster: null }
];

test("with no plugin no cluster is built, whatever the streams say", () => {
	internals.resetClusterFactory();
	assert.deepStrictEqual(internals.clustersOf(STREAMS), {});
	assert.deepStrictEqual(internals.clustersOf([]), {});
	assert.deepStrictEqual(internals.clustersOf(null), {});
});

test("the core no longer carries the cluster arithmetic", () => {
	assert.strictEqual(internals.ClusterRates, undefined);
	assert.strictEqual(internals.canonicalCommand, undefined);
});

test("an installed factory is called once per cluster, with that cluster's members in edge order", () => {
	const calls = [];
	FcDashboard.setClusterFactory((name, members) => {
		calls.push([name, members]);
		return { name: name, members: members };
	});
	try {
		const clusters = internals.clustersOf(STREAMS);
		assert.deepStrictEqual(calls, [["Edge layer", ["https://fc-edge.example.com:443/", "https://fc-eu.example.com:443/"]]]);
		assert.deepStrictEqual(Object.keys(clusters), ["Edge layer"]);
	} finally {
		internals.resetClusterFactory();
	}
});
