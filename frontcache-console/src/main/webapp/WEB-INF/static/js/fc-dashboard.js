/*
 * Realtime monitor for the Frontcache console.
 *
 * Replaces the vendored Netflix Hystrix dashboard (hystrixCommand.js + hystrixThreadPool.js + their
 * templates, d3 v2, jQuery 1.7, tinysort, tmpl.js, and underscore off a CDN). This file has no
 * dependencies at all - it is the whole monitor.
 *
 * (realtime.jsp still loads jQuery + bootstrap.js for the console's navbar, as every other console page
 * does. That is the console's chrome, not the monitor, and vendoring it is a separate job.)
 *
 * ---------------------------------------------------------------------------------------------
 * The wire format is NOT ours to change here.
 *
 * The console is deployed separately from the nodes it monitors, so this has to read a 2.6 node's
 * frames as well as a 2.7 one's - and 'type':'HystrixCommand' is pinned indefinitely anyway, because
 * an external Hystrix Dashboard or Turbine may be reading the same stream (see
 * docs/archive/hystrix-to-resilience4j-proposal.md sections 7.1 and 8.2). So the field names below are the
 * documented contract, deliberately unchanged; only the renderer was replaced.
 *
 * What did change is what happens when a field is absent. The vendored dashboard asserted 36 keys and
 * threw on the first missing one, which blanked the whole panel with nothing but a browser-console
 * error - the failure mode section 7 calls the highest risk of the migration. Nothing here throws: a
 * missing number renders as a dash and the rest of the card still draws.
 * ---------------------------------------------------------------------------------------------
 */
var FcDashboard = (function () {
	"use strict";

	var SPARKLINE_WIDTH = 140;
	var SPARKLINE_HEIGHT = 60;
	var SPARKLINE_WINDOW_MS = 2 * 60 * 1000;
	var SPARKLINE_MAX_POINTS = 200;

	var SVG_NS = "http://www.w3.org/2000/svg";

	// ---- number handling -----------------------------------------------------------------------

	/** A number from the frame, or null when the node did not send it. Never throws. */
	function num(data, key) {
		var value = data[key];
		if (value === undefined || value === null)
			return null;
		value = Number(value);
		return isNaN(value) ? null : value;
	}

	function integer(data, key) {
		var value = num(data, key);
		return value === null ? null : Math.floor(value);
	}

	/** Renders a possibly-absent number: absent is "-", not 0 - a zero would be a claim we cannot make. */
	function show(value) {
		return value === null ? "-" : addCommas(value);
	}

	function addCommas(value) {
		return String(value).replace(/(\d)(?=(\d\d\d)+(?!\d))/g, "$1,");
	}

	function round1(value) {
		var result = Math.round(value * 10) / 10;
		var text = String(result);
		return text.indexOf(".") === -1 ? text + ".0" : text;
	}

	function clamp(value, min, max) {
		return value < min ? min : (value > max ? max : value);
	}

	// ---- cluster aggregation -------------------------------------------------------------------
	//
	// The page already holds an open EventSource per edge, so an aggregate over a group of them costs
	// no connection and no request to the fleet: the frames are here. See
	// docs/circuit-cluster-proposal.md section 3 for why this is not done in the console or on the nodes.

	/** The node's own publish interval (FcSampleSseServlet.DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS). */
	var CLUSTER_TICK_MS = 500;

	/**
	 * How long a member's last frame for a command still counts.
	 *
	 * Ten node ticks. Without an expiry a node that goes away freezes its last frame into the cluster
	 * total for as long as the page is open - the panel would keep reporting traffic from a dead
	 * region. With it the cluster rate FALLS when a member drops out, which is the correct answer and
	 * is what that member's own panel switching to "retrying ..." explains.
	 */
	var MEMBER_STALE_MS = 5000;

	/**
	 * Pre-2.9.0 command key -> the key that replaced it. Mirrors FcCommandKeys.LEGACY_COMMAND_KEYS.
	 *
	 * The console reads a MIXED fleet, so mid-upgrade one member emits Input-Request and another
	 * Input-Requests. Merging those as two commands produces two cluster cards, each claiming one
	 * reporting host, neither of them the site's traffic - and the row grows and shrinks as the
	 * upgrade rolls. Canonicalizing first is what keeps one card per command.
	 *
	 * Cache-Miss is two legacy keys folding into ONE, and on a pre-2.9.0 filter-mode node both are
	 * live at once (the filter chain serves the page's origin call, HTTP serves its includes). So a
	 * single member can contribute two frames to one canonical command - which is why mergeFrames
	 * folds per member first and counts MEMBERS, not frames.
	 *
	 * This is the map's third copy: FcCommandKeys, the `order` block in fc-dashboard.css, and here.
	 * Worth saying out loud; the CSS copy is the precedent, and the alternative is a wire addition to
	 * carry six rows.
	 */
	var CANONICAL_COMMAND = {
		"Input-Requests": "Input-Request",
		"Include-Requests": "Include-Request",
		"Cache-Hits": "Cache-Hit",
		"Cache-Origin-http": "Cache-Miss",
		"Cache-Origin-filter": "Cache-Miss",
		"Origin-Hits": "Cache-Bypass"
	};

	function canonicalCommand(name) {
		return CANONICAL_COMMAND[name] || name;
	}

	var WINDOW_KEY = "propertyValue_metricsRollingStatisticalWindowInMilliseconds";
	var DEFAULT_WINDOW_MS = 10000;

	/** The nine percentiles a frame carries - FcDashboardJson.PERCENTILE_KEYS, in the same order. */
	var PERCENTILE_KEYS = ["0", "25", "50", "75", "90", "95", "99", "99.5", "100"];

	/** Summed as event counts. Anything named rollingCount* included, so an unknown counter still adds up. */
	function isCountKey(key) {
		return key.indexOf("rollingCount") === 0
				|| key === "errorCount"
				|| key === "currentConcurrentExecutionCount"
				|| key === "rollingMaxConcurrentExecutionCount";
	}

	/**
	 * Fields the renderer DIVIDES by reportingHosts (see Circuit.update), so across members they are
	 * summed - a sum renders as the mean across hosts. Not summing them would show one member's
	 * latency labelled as the cluster's.
	 */
	var LATENCY_MEAN_KEYS = ["latencyExecute_mean", "latencyTotal_mean"];
	var LATENCY_PERCENTILE_KEYS = ["latencyExecute", "latencyTotal"];

	/** Taken as the maximum: a threshold or a timeout that differs across members is not a sum. */
	function isMaxKey(key) {
		return key.indexOf("propertyValue_") === 0 || key === "currentTime";
	}

	function windowOf(frame) {
		return num(frame, WINDOW_KEY) || DEFAULT_WINDOW_MS;
	}

	/**
	 * Combines frames for ONE command key into one frame.
	 *
	 * @param frames         one or more frames, all for the same canonical command
	 * @param hosts          how many distinct members they came from - what reportingHosts must report
	 * @param averageLatency true when the frames are all from the SAME member (the Cache-Miss fold),
	 *                       where the latency fields are averaged rather than summed. Summing them
	 *                       there and then dividing by one host would double that member's latency.
	 */
	function combineFrames(frames, hosts, averageLatency) {

		var merged = { type: "HystrixCommand", name: canonicalCommand(frames[0].name) };

		if (frames[0].group !== undefined)
			merged.group = frames[0].group;
		if (frames[0].threadPool !== undefined)
			merged.threadPool = frames[0].threadPool;

		// ---- the divisor. NEVER a sum: the renderer computes requestCount / (window/1000), so a
		// summed window turns a three-node cluster's traffic into a third of it, on the one screen
		// whose purpose is that number, with nothing to say so. Take the widest window, and normalize
		// each member's count into it so the answer is right even when the fleet's windows disagree.
		var windowMs = 0;
		var windowsDiffer = false;
		frames.forEach(function (frame) {
			var w = windowOf(frame);
			if (windowMs && w !== windowMs)
				windowsDiffer = true;
			windowMs = Math.max(windowMs, w);
		});
		merged[WINDOW_KEY] = windowMs;

		if (windowsDiffer)
			warnOnce("window:" + merged.name, "The members reporting " + merged.name
					+ " do not agree on resilience.command.<key>.metrics.rollingStats.timeInMilliseconds."
					+ " The cluster rate is normalized to " + windowMs + " ms, but the counters are raw sums.");

		var normalizedRequests = 0;
		var rawRequests = 0;
		var rawErrors = 0;
		var anyRequestCount = false;

		frames.forEach(function (frame) {
			var count = num(frame, "requestCount");
			if (count === null)
				return;
			anyRequestCount = true;
			normalizedRequests += count / windowOf(frame);
			rawRequests += count;
			rawErrors += num(frame, "errorCount") || 0;
		});

		if (anyRequestCount)
			merged.requestCount = Math.round(normalizedRequests * windowMs);

		// ---- counts
		var counts = {};
		frames.forEach(function (frame) {
			for (var key in frame)
				if (frame.hasOwnProperty(key) && isCountKey(key) && key !== "requestCount")
					counts[key] = (counts[key] || 0) + (num(frame, key) || 0);
		});
		for (var countKey in counts)
			if (counts.hasOwnProperty(countKey))
				merged[countKey] = counts[countKey];

		// ---- error percentage
		//
		// Recomputed from the summed counts rather than summed from the members' own percentages, then
		// multiplied by the host count because the renderer divides by it. An average of percentages
		// weights a node taking 2 requests the same as one taking 2000; errorCount / requestCount is
		// the definition FcCommandMetrics.getErrorPercentage uses and is properly weighted. Falls back
		// to the sum for a frame that reports a percentage without the counts behind it.
		if (rawRequests > 0)
			merged.errorPercentage = Math.round(rawErrors * 100 / rawRequests) * hosts;
		else {
			var reported = null;
			frames.forEach(function (frame) {
				var value = num(frame, "errorPercentage");
				if (value !== null)
					reported = (reported || 0) + value;
			});
			if (reported !== null)
				merged.errorPercentage = reported;
		}

		// ---- latency
		var weights = frames.map(function (frame) { return Math.max(0, num(frame, "requestCount") || 0); });
		var totalWeight = weights.reduce(function (a, b) { return a + b; }, 0);

		var fold = function (values) {
			// null when no frame reported this field at all - so the card shows a dash rather than a
			// zero, which would be a claim we cannot make.
			var present = false, sum = 0, weighted = 0;
			values.forEach(function (value, index) {
				if (value === null)
					return;
				present = true;
				sum += value;
				weighted += value * (totalWeight > 0 ? weights[index] / totalWeight : 1 / values.length);
			});
			if (!present)
				return null;
			return averageLatency ? Math.round(weighted) : sum;
		};

		LATENCY_MEAN_KEYS.forEach(function (key) {
			var folded = fold(frames.map(function (frame) { return num(frame, key); }));
			if (folded !== null)
				merged[key] = folded;
		});

		LATENCY_PERCENTILE_KEYS.forEach(function (key) {
			var percentiles = {};
			var any = false;
			PERCENTILE_KEYS.forEach(function (percentile) {
				var folded = fold(frames.map(function (frame) {
					var map = frame[key];
					return map ? num(map, percentile) : null;
				}));
				if (folded !== null) {
					percentiles[percentile] = folded;
					any = true;
				}
			});
			if (any)
				merged[key] = percentiles;
		});

		// ---- the circuit state, as a COUNT of open members.
		//
		// This is what lights up circuitStatus's "Circuit 1 of 3 Open" - the branch that has never been
		// reachable, because a node's own frame carries a boolean and reportingHosts:1. Note the order
		// of the tests there: `=== true` is checked BEFORE the numeric comparison, so this must be a
		// number and never true, or one tripped member reads as the whole cluster being open.
		var open = null;
		frames.forEach(function (frame) {
			if (frame.isCircuitBreakerOpen === undefined || frame.isCircuitBreakerOpen === null)
				return;
			open = (open || 0) + (frame.isCircuitBreakerOpen === true || num(frame, "isCircuitBreakerOpen") >= 1 ? 1 : 0);
		});
		if (open !== null)
			merged.isCircuitBreakerOpen = averageLatency ? Math.min(1, open) : open;

		// ---- property values: max, not sum
		var maxima = {};
		frames.forEach(function (frame) {
			for (var key in frame) {
				if (!frame.hasOwnProperty(key) || !isMaxKey(key) || key === WINDOW_KEY)
					continue;
				var value = num(frame, key);
				if (value === null)
					continue;
				if (maxima[key] === undefined)
					maxima[key] = value;
				else if (value !== maxima[key]) {
					maxima[key] = Math.max(maxima[key], value);
					if (key !== "currentTime")
						warnOnce("property:" + merged.name + ":" + key, "The members reporting "
								+ merged.name + " do not agree on " + key
								+ ". The cluster card shows the highest value.");
				}
			}
		});
		for (var maxKey in maxima)
			if (maxima.hasOwnProperty(maxKey))
				merged[maxKey] = maxima[maxKey];

		// Forced open / forced closed only when EVERY member says so - a flag one member sets is that
		// member's state, and the open count above is where it belongs.
		["propertyValue_circuitBreakerForceOpen", "propertyValue_circuitBreakerForceClosed"].forEach(function (key) {
			var all = frames.every(function (frame) { return frame[key] === true; });
			if (all || frames.some(function (frame) { return frame[key] !== undefined; }))
				merged[key] = all;
		});

		// Which rejection counter the card shows hangs off this. Prefer THREAD when members disagree:
		// it is the one with a real number behind it, since a SEMAPHORE command has no pool to be
		// rejected by.
		var strategy = null;
		frames.forEach(function (frame) {
			var value = frame.propertyValue_executionIsolationStrategy;
			if (value === undefined || value === null)
				return;
			if (strategy === null || value === "THREAD")
				strategy = value;
		});
		if (strategy !== null)
			merged.propertyValue_executionIsolationStrategy = strategy;

		merged.reportingHosts = hosts;

		return merged;
	}

	/**
	 * One cluster card's frame, from every member's latest frame for that command.
	 *
	 * Frames from different members are simply summed, and the reason that is sound is worth being
	 * explicit about: every count in a frame is a windowed sum over the node's own rolling window
	 * (10 s by default), not a delta since its last frame. So merging frames that arrived at
	 * different moments double-counts nothing, and the most a member can contribute is one 500 ms
	 * tick of staleness inside a twenty-tick window.
	 *
	 * @param entries [{url, frame, at}] - a member may appear more than once (the Cache-Miss fold)
	 * @return the merged frame, or null when every entry is stale
	 */
	function mergeFrames(entries, nowMs, staleMs) {

		var byMember = {};
		var members = [];

		entries.forEach(function (entry) {
			if (nowMs - entry.at > (staleMs === undefined ? MEMBER_STALE_MS : staleMs))
				return;
			if (!byMember[entry.url]) {
				byMember[entry.url] = [];
				members.push(entry.url);
			}
			byMember[entry.url].push(entry.frame);
		});

		if (!members.length)
			return null;

		var perMember = members.map(function (url) {
			var frames = byMember[url];
			return frames.length === 1 ? frames[0] : combineFrames(frames, 1, true);
		});

		return combineFrames(perMember, members.length, false);
	}

	/** A fleet-config inconsistency is worth saying once, not twice a second. */
	var warned = {};

	function warnOnce(key, message) {
		if (warned[key])
			return;
		warned[key] = true;
		if (window.console && console.warn)
			console.warn("[frontcache] " + message);
	}

	// ---- colour ramps --------------------------------------------------------------------------
	// Piecewise-linear RGB over a sorted domain, clamped at both ends. The vendored dashboard got this
	// from d3.scale.linear(); the stops below are its exact values, so the panel keeps its colours.

	function ramp(domain, colors) {
		return function (value) {
			if (value === null)
				return colors[0];
			if (value <= domain[0])
				return colors[0];
			if (value >= domain[domain.length - 1])
				return colors[colors.length - 1];

			for (var i = 1; i < domain.length; i++)
				if (value <= domain[i]) {
					var t = (value - domain[i - 1]) / (domain[i] - domain[i - 1]);
					return mixRgb(colors[i - 1], colors[i], t);
				}

			return colors[colors.length - 1];
		};
	}

	var NAMED = { green: [0, 128, 0], red: [255, 0, 0], grey: [128, 128, 128], black: [0, 0, 0] };

	function toRgb(color) {
		if (NAMED[color])
			return NAMED[color];
		return [parseInt(color.substr(1, 2), 16), parseInt(color.substr(3, 2), 16), parseInt(color.substr(5, 2), 16)];
	}

	function mixRgb(from, to, t) {
		var a = toRgb(from), b = toRgb(to);
		var channel = function (i) { return Math.round(a[i] + (b[i] - a[i]) * t); };
		return "rgb(" + channel(0) + "," + channel(1) + "," + channel(2) + ")";
	}

	var circuitColor = ramp([10, 25, 40, 50], ["green", "#FFCC00", "#FF9900", "red"]);
	var errorPercentageColor = ramp([0, 10, 35, 50], ["grey", "black", "#FF9900", "red"]);

	/** Bubble radius by request rate: sqrt-scaled over 0..400/s onto 5..125px, as the vendored one was. */
	function bubbleRadius(rate) {
		return 5 + 120 * Math.sqrt(clamp(rate, 0, 400) / 400);
	}

	/** Bubble centre drifts 30% -> 40% as the rate climbs, so a busy circuit is not clipped by the box. */
	function bubbleCentre(rate) {
		return (30 + 10 * clamp(rate, 0, 400) / 400) + "%";
	}

	// ---- small DOM helpers ---------------------------------------------------------------------

	function el(tag, className, text) {
		var node = document.createElement(tag);
		if (className)
			node.className = className;
		if (text !== undefined && text !== null)
			node.textContent = text;
		return node;
	}

	function svg(tag, attributes) {
		var node = document.createElementNS(SVG_NS, tag);
		for (var key in attributes)
			if (attributes.hasOwnProperty(key))
				node.setAttribute(key, attributes[key]);
		return node;
	}

	function counter(className, title, value) {
		var node = el("span", className, show(value));
		node.title = title;
		return node;
	}

	// ---- one circuit card ----------------------------------------------------------------------

	function Circuit(commandName) {

		var self = this;

		self.name = commandName;
		self.points = [];

		self.root = el("div", "fc-circuit");
		self.root.setAttribute("data-command", commandName);

		var chart = svg("svg", { "class": "fc-circuit-chart" });
		self.bubble = svg("circle", { cx: "30%", cy: "30%", r: "5", fill: "green" });
		self.sparkline = svg("path", { "class": "fc-sparkline", transform: "translate(0,30)" });
		chart.appendChild(self.bubble);
		chart.appendChild(self.sparkline);
		self.root.appendChild(chart);

		self.body = el("div", "fc-circuit-body");
		self.root.appendChild(self.body);

		// the name never changes, so it is written once rather than on every 500 ms frame
		var displayName = commandName.length > 32
				? commandName.substring(0, 4) + "..." + commandName.substring(commandName.length - 20)
				: commandName;
		var nameNode = el("p", "fc-name", displayName);
		nameNode.title = commandName;
		self.body.appendChild(nameNode);

		self.data = el("div", "fc-circuit-data");
		self.body.appendChild(self.data);
	}

	Circuit.prototype.update = function (frame) {

		var self = this;

		var reportingHosts = num(frame, "reportingHosts") || 1;
		var windowMs = num(frame, "propertyValue_metricsRollingStatisticalWindowInMilliseconds") || 10000;
		var requestCount = Math.max(0, num(frame, "requestCount") || 0);

		var ratePerSecond = requestCount / (windowMs / 1000);
		var ratePerSecondPerHost = ratePerSecond / reportingHosts;

		var errorPercentage = num(frame, "errorPercentage");
		if (errorPercentage !== null)
			errorPercentage = Math.round(errorPercentage / reportingHosts * 10) / 10;

		self.rate = ratePerSecond;

		self.data.textContent = "";
		self.data.appendChild(counters(frame, errorPercentage));
		self.data.appendChild(rateLine("Host: ", ratePerSecondPerHost, "Total Request Rate per Second per Reporting Host"));

		// One reporting host means the two lines are the same number by construction, and the second
		// one then says nothing but "this is one node" - which was true of every card on this page
		// before cluster panels existed. The Cluster line is rendered where it is being summed.
		if (reportingHosts > 1)
			self.data.appendChild(rateLine("Cluster: ", ratePerSecond, "Total Request Rate per Second for Cluster"));
		self.data.appendChild(circuitStatus(frame, reportingHosts));
		self.data.appendChild(latencyTable(frame, reportingHosts));

		self.bubble.setAttribute("r", String(bubbleRadius(ratePerSecondPerHost)));
		self.bubble.setAttribute("cx", bubbleCentre(ratePerSecondPerHost));
		self.bubble.setAttribute("cy", bubbleCentre(ratePerSecondPerHost));
		self.bubble.setAttribute("fill", circuitColor(errorPercentage));

		self.pushPoint(ratePerSecond);
	};

	/** Rolls the sparkline window forward one frame and redraws the path. */
	Circuit.prototype.pushPoint = function (value) {

		var self = this;
		var now = Date.now();

		self.points.push({ v: value, t: now });

		while (self.points.length > SPARKLINE_MAX_POINTS || (self.points.length > 0 && now - self.points[0].t > SPARKLINE_WINDOW_MS))
			self.points.shift();

		// a lone zero is not a line worth drawing
		if (self.points.length < 2) {
			self.sparkline.setAttribute("d", "");
			return;
		}

		var min = self.points[0].v, max = self.points[0].v;
		for (var i = 1; i < self.points.length; i++) {
			min = Math.min(min, self.points[i].v);
			max = Math.max(max, self.points[i].v);
		}
		var span = (max - min) || 1;

		var d = "";
		for (var j = 0; j < self.points.length; j++) {
			var x = SPARKLINE_WIDTH * (1 - (now - self.points[j].t) / SPARKLINE_WINDOW_MS);
			var y = SPARKLINE_HEIGHT * (1 - (self.points[j].v - min) / span);
			d += (j === 0 ? "M" : "L") + round1(clamp(x, 0, SPARKLINE_WIDTH)) + "," + round1(y);
		}

		self.sparkline.setAttribute("d", d);
	};

	function counters(frame, errorPercentage) {

		var node = el("div", "fc-counters");

		var percentage = el("span", "fc-error-percentage",
				(errorPercentage === null ? "-" : errorPercentage) + " %");
		percentage.title = "Error Percentage [Timed-out + Rejected + Failure / Total]";
		percentage.style.color = errorPercentageColor(errorPercentage);

		var percentageCell = el("div", "fc-cell");
		percentageCell.appendChild(percentage);
		node.appendChild(percentageCell);

		var errors = el("div", "fc-cell fc-border-right");
		errors.appendChild(counter("fc-timeout", "Timed-out Request Count", integer(frame, "rollingCountTimeout")));

		// which rejection counter is meaningful depends on how the command is isolated: a semaphore
		// command has no thread pool to be rejected by, and vice versa
		if ("SEMAPHORE" === frame.propertyValue_executionIsolationStrategy)
			errors.appendChild(counter("fc-rejected", "Semaphore Rejected Request Count", integer(frame, "rollingCountSemaphoreRejected")));
		else
			errors.appendChild(counter("fc-rejected", "Threadpool Rejected Request Count", integer(frame, "rollingCountThreadPoolRejected")));

		errors.appendChild(counter("fc-failure", "Failure Request Count", integer(frame, "rollingCountFailure")));

		var successes = el("div", "fc-cell fc-border-right");
		successes.appendChild(counter("fc-success", "Successful Request Count", integer(frame, "rollingCountSuccess")));
		successes.appendChild(counter("fc-short-circuited", "Short-circuited Request Count", integer(frame, "rollingCountShortCircuited")));
		successes.appendChild(counter("fc-bad-request", "Bad Request Count", integer(frame, "rollingCountBadRequests")));

		// right-aligned, so the rightmost cell is appended first
		node.appendChild(successes);
		node.appendChild(errors);

		return node;
	}

	function rateLine(label, value, title) {

		var node = el("div", "fc-rate");
		node.title = title;
		node.appendChild(el("span", "fc-smaller", label));
		node.appendChild(el("span", "fc-value", round1(value)));
		node.appendChild(document.createTextNode("/s"));

		return node;
	}

	function circuitStatus(frame, reportingHosts) {

		var node = el("div", "fc-circuit-status");

		if (frame.propertyValue_circuitBreakerForceClosed)
			node.appendChild(el("span", "", "[ Forced Closed ] "));

		var state = el("span");
		var open = num(frame, "isCircuitBreakerOpen");

		if (frame.propertyValue_circuitBreakerForceOpen) {
			state.textContent = "Circuit Forced Open";
			state.style.color = "red";
		} else if (frame.isCircuitBreakerOpen === undefined) {
			state.textContent = "Circuit -";
			state.style.color = "grey";
		} else if (frame.isCircuitBreakerOpen === true || open === reportingHosts) {
			state.textContent = "Circuit Open";
			state.style.color = "red";
		} else if (frame.isCircuitBreakerOpen === false || open === 0) {
			state.textContent = "Circuit Closed";
			state.style.color = "green";
		} else {
			// an aggregated stream where only some hosts have tripped
			state.textContent = "Circuit " + open + " of " + reportingHosts + " Open";
			state.style.color = "orange";
		}

		node.appendChild(state);
		return node;
	}

	function latencyTable(frame, reportingHosts) {

		var percentiles = frame.latencyExecute || {};

		var perHost = function (value) {
			if (value === undefined || value === null || isNaN(Number(value)))
				return "-";
			return String(Math.floor(Number(value) / reportingHosts));
		};

		var mean = num(frame, "latencyExecute_mean");

		var table = el("table", "fc-latency");
		var body = document.createElement("tbody");

		var row = function (leftHeader, leftValue, rightHeader, rightValue) {
			var tr = document.createElement("tr");
			tr.appendChild(el("th", "", leftHeader));
			tr.appendChild(el("td", "", leftValue));
			tr.appendChild(el("th", "", rightHeader));
			tr.appendChild(el("td", "", rightValue));
			return tr;
		};

		var ms = function (value) { return value + " ms"; };

		body.appendChild(row(reportingHosts > 1 ? "Hosts" : "Host",
				reportingHosts > 1 ? String(reportingHosts) : "Single",
				"90th", ms(perHost(percentiles["90"]))));
		body.appendChild(row("Median", ms(perHost(percentiles["50"])), "99th", ms(perHost(percentiles["99"]))));
		body.appendChild(row("Mean", ms(mean === null ? "-" : Math.floor(mean / reportingHosts)),
				"99.5th", ms(perHost(percentiles["99.5"]))));

		table.appendChild(body);
		return table;
	}

	// ---- the row of cards, shared by an edge panel and a cluster panel -------------------------

	/**
	 * A row of circuit cards, keyed by command name. Owns the cards' lifecycle and nothing else -
	 * where the frames come from is the panel's business, which is what lets a cluster panel reuse
	 * every pixel of the per-edge one (including the CSS `order` block that puts the five known
	 * commands in request-travel order).
	 */
	function CircuitBoard() {

		this.circuits = {};
		this.node = el("div", "fc-circuits");
	}

	CircuitBoard.prototype.update = function (commandName, frame) {

		var circuit = this.circuits[commandName];

		if (!circuit) {
			circuit = new Circuit(commandName);
			this.circuits[commandName] = circuit;
			this.insertSorted(circuit);
		}

		circuit.update(frame);
	};

	CircuitBoard.prototype.has = function (commandName) {
		return !!this.circuits[commandName];
	};

	/**
	 * How many cards this row holds. The row is a responsive grid that fits as many cards per row as
	 * the column width allows (see .fc-circuits in fc-dashboard.css); this is what stops it from
	 * reserving room for cards that do not exist.
	 */
	CircuitBoard.prototype.syncCardCount = function () {
		this.node.style.setProperty("--fc-cards", this.node.children.length);
	};

	/** Alphabetical, which was the vendored panel's default sort. CSS `order` then pins the known commands. */
	CircuitBoard.prototype.insertSorted = function (circuit) {

		var existing = this.node.children;

		for (var i = 0; i < existing.length; i++)
			if (existing[i].getAttribute("data-command") > circuit.name) {
				this.node.insertBefore(circuit.root, existing[i]);
				this.syncCardCount();
				return;
			}

		this.node.appendChild(circuit.root);
		this.syncCardCount();
	};

	CircuitBoard.prototype.remove = function (commandName) {

		var circuit = this.circuits[commandName];
		if (!circuit)
			return;

		this.node.removeChild(circuit.root);
		delete this.circuits[commandName];
		this.syncCardCount();
	};

	function legendNode() {

		var legend = el("div", "fc-legend");
		legend.appendChild(el("span", "fc-legend-title", "Circuits"));
		[["fc-success", "Success"], ["fc-short-circuited", "Short-Circuited"], ["fc-bad-request", "Bad Request"],
		 ["fc-timeout", "Timeout"], ["fc-rejected", "Rejected"], ["fc-failure", "Failure"]]
			.forEach(function (entry, index) {
				if (index > 0)
					legend.appendChild(document.createTextNode(" | "));
				legend.appendChild(el("span", entry[0], entry[1]));
			});

		return legend;
	}

	// ---- one edge's stream ---------------------------------------------------------------------

	/**
	 * @param aggregator the cluster this edge belongs to, or null. Every frame is rendered on this
	 *                   panel AND handed to the aggregator - one EventSource either way.
	 */
	function StreamPanel(container, stream, aggregator) {

		var self = this;

		self.url = stream.url;
		self.aggregator = aggregator || null;
		self.board = new CircuitBoard();

		self.root = el("div", "fc-dashboard");
		self.root.appendChild(legendNode());

		self.root.appendChild(el("div", "fc-stream-name", stream.name || stream.url));

		if (self.aggregator)
			self.root.appendChild(el("div", "fc-cluster-member", "in cluster \u201c" + self.aggregator.name + "\u201d"));

		self.status = el("span", "fc-status", "Connecting ...");
		self.root.appendChild(self.status);

		self.root.appendChild(self.board.node);

		container.appendChild(self.root);

		self.connect(stream.url);
	}

	StreamPanel.prototype.connect = function (edgeUrl) {

		var self = this;

		// The proxy takes the EDGE url and appends the stream path itself; the browser does not get to
		// choose what is fetched from the node, and only edges the console already knows are contacted.
		var source = new EventSource("fc-dashboard-proxy.stream?origin=" + encodeURIComponent(edgeUrl));

		source.addEventListener("message", function (event) {
			self.status.style.display = "none";
			self.onFrame(event.data);
		}, false);

		source.addEventListener("error", function () {
			// EventSource reconnects on its own; say so rather than leaving a dead "Loading ..."
			self.status.style.display = "";
			self.status.className = "fc-status failed";
			self.status.textContent = "Unable to read the metrics stream from " + edgeUrl + " - retrying ...";

			// One of the reasons for that error is that the session expired while the page was
			// open, and EventSource never surfaces it: it does not expose the HTTP status and it
			// retries a 401 forever, so the page would sit on "retrying ..." for good. Probe a
			// normal endpoint once per error event to find out. See docs/archive/console-login-ui.md
			// section 9.
			probeForSignOut();
		}, false);
	};

	/**
	 * Hand the browser to the login page if - and only if - the console is answering 401.
	 *
	 * Any other outcome (the node is down, the relay dropped, the console is unreachable) is left
	 * to the "retrying ..." message above, which is the honest description of it.
	 *
	 * At most one probe is in flight: an error event fires per reconnect, per panel, and a fleet of
	 * edges behind a signed-out console would otherwise issue a burst of them.
	 */
	var signOutProbeInFlight = false;

	function probeForSignOut() {

		if (signOutProbeInFlight || !window.fetch)
			return;

		signOutProbeInFlight = true;

		fetch("edges-status.json", { credentials: "same-origin", headers: { "Accept": "application/json" } })
			.then(function (response) {
				if (401 === response.status)
				{
					// The last path segment only - see the same note in fc-edges-status.js.
					var path = window.location.pathname;
					var here = path.substring(path.lastIndexOf("/")) + window.location.search;

					window.location.replace("login?next=" + encodeURIComponent(here));
				}
			})
			.catch(function () {
				// The console itself is unreachable. Not a sign-out; the panel already says so.
			})
			.then(function () {
				signOutProbeInFlight = false;
			});
	}

	StreamPanel.prototype.onFrame = function (raw) {

		var self = this;
		var frame;

		try {
			frame = JSON.parse(raw);
		} catch (e) {
			return;
		}

		// Thread-pool frames ride the same stream. The console has not rendered them since the pool
		// section was commented out; frontcache_threadpool_* in the Prometheus export is where those
		// numbers live now (docs/archive/hystrix-to-resilience4j-proposal.md section 7.5).
		if (!frame || "HystrixCommand" !== frame.type || !frame.name)
			return;

		if ("true" === String(frame.deleteData)) {
			self.board.remove(frame.name);
			if (self.aggregator)
				self.aggregator.drop(self.url, frame.name);
			return;
		}

		self.board.update(frame.name, frame);

		if (self.aggregator)
			self.aggregator.accept(self.url, frame);
	};

	// ---- one cluster -----------------------------------------------------------------------------

	/**
	 * The aggregate over a group of edges: one panel, one card per command, summed across the
	 * members - what `Cluster:` on a card has been claiming to be since 2.7 and could not be.
	 *
	 * It opens no connection of its own. Each member's StreamPanel pushes the frames it is already
	 * receiving into here, so a cluster costs one setInterval and no traffic to the fleet.
	 */
	function ClusterPanel(container, name, memberUrls) {

		var self = this;

		self.name = name;
		self.board = new CircuitBoard();

		// command name -> member url -> {frame, at}
		self.latest = {};

		self.root = el("div", "fc-dashboard fc-cluster");
		self.root.appendChild(legendNode());

		var heading = el("div", "fc-stream-name");
		heading.appendChild(el("span", "fc-cluster-name", name));
		heading.appendChild(document.createTextNode(" \u2014 " + memberUrls.length + " nodes: "
				+ memberUrls.map(hostOf).join(", ")));
		self.root.appendChild(heading);

		self.status = el("span", "fc-status", "Waiting for the first frames from " + memberUrls.length + " nodes ...");
		self.root.appendChild(self.status);

		self.root.appendChild(self.board.node);

		container.appendChild(self.root);

		// A fixed tick, not a recompute per arrival. Three members publishing every 500 ms would push
		// six sparkline points a second into a card that holds SPARKLINE_MAX_POINTS, quietly shrinking
		// its window from two minutes to about thirty seconds - so the cluster card would be plotting a
		// different span of time than the member cards directly below it. The tick also drives staleness:
		// without it a cluster whose members all went silent would sit on its last total for good.
		self.timer = window.setInterval(function () { self.tick(); }, CLUSTER_TICK_MS);
	}

	/**
	 * An edge url's host AND port, for the cluster heading - the full urls would wrap the line.
	 *
	 * The port stays. Dropping it would be FrontCacheClient.getName(), which strips the scheme and
	 * everything from the first colon, so localhost:8080 and localhost:9080 - the e2e topology - come
	 * out as one name; a cluster heading listing the same node twice is the mildest form of that bug.
	 */
	function hostOf(edgeUrl) {
		return String(edgeUrl).replace(/^[a-z]+:\/\//i, "").replace(/\/.*$/, "");
	}

	ClusterPanel.prototype.accept = function (memberUrl, frame) {

		var command = canonicalCommand(frame.name);

		if (!this.latest[command])
			this.latest[command] = {};

		// Keyed by member AND by the key as it arrived, so a pre-2.9.0 filter node's Cache-Origin-http
		// and Cache-Origin-filter are two contributions to one canonical command rather than one
		// overwriting the other.
		this.latest[command][memberUrl + " " + frame.name] = { url: memberUrl, frame: frame, at: Date.now() };
	};

	ClusterPanel.prototype.drop = function (memberUrl, commandName) {

		var command = canonicalCommand(commandName);
		var contributions = this.latest[command];

		if (contributions)
			delete contributions[memberUrl + " " + commandName];
	};

	ClusterPanel.prototype.tick = function () {

		var self = this;
		var now = Date.now();
		var rendered = 0;

		for (var command in self.latest) {

			if (!self.latest.hasOwnProperty(command))
				continue;

			var entries = [];
			var contributions = self.latest[command];

			for (var key in contributions)
				if (contributions.hasOwnProperty(key)) {
					if (now - contributions[key].at > MEMBER_STALE_MS)
						delete contributions[key];
					else
						entries.push(contributions[key]);
				}

			var merged = entries.length ? mergeFrames(entries, now, MEMBER_STALE_MS) : null;

			if (merged) {
				self.board.update(command, merged);
				rendered++;
			} else if (self.board.has(command)) {
				self.board.remove(command);
			}
		}

		if (rendered) {
			self.status.style.display = "none";
		} else {
			self.status.style.display = "";
			self.status.className = "fc-status failed";
			self.status.textContent = "No member of \u201c" + self.name + "\u201d is reporting.";
		}
	};

	// ---- entry point ---------------------------------------------------------------------------

	return {
		/**
		 * @param container  the element to render into
		 * @param streams    [{name, url, cluster}] - one per edge, from the console's edge list.
		 *                   cluster is null for an edge in none, which is every edge on a console
		 *                   config that predates frontcache.console.clusters.
		 *
		 * Order down the page is cluster aggregate, then that cluster's members, then the unclustered
		 * edges - which for the topology this was built for (a load balancer over three layer-1 nodes
		 * in front of one filter node) reads in the order a request travels.
		 *
		 * The member panels are kept rather than replaced by the aggregate. The cluster answers "how
		 * much traffic, and is anything open"; only the per-node panels answer "which node", and that
		 * is the question asked next.
		 */
		attach: function (container, streams) {

			if (!streams || !streams.length) {
				container.appendChild(el("span", "fc-status", "No edges configured."));
				return;
			}

			var clusterNames = [];
			var membersByCluster = {};

			streams.forEach(function (stream) {
				if (!stream.cluster)
					return;
				if (!membersByCluster[stream.cluster]) {
					membersByCluster[stream.cluster] = [];
					clusterNames.push(stream.cluster);
				}
				membersByCluster[stream.cluster].push(stream);
			});

			clusterNames.forEach(function (name) {

				var members = membersByCluster[name];

				var aggregator = new ClusterPanel(container, name, members.map(function (stream) {
					return stream.url;
				}));

				members.forEach(function (stream) {
					new StreamPanel(container, stream, aggregator);
				});
			});

			streams.forEach(function (stream) {
				if (!stream.cluster)
					new StreamPanel(container, stream, null);
			});
		},

		// exposed for the console's own tests, not for page code
		_internals: {
			num: num, ramp: ramp, bubbleRadius: bubbleRadius, addCommas: addCommas,
			mergeFrames: mergeFrames, canonicalCommand: canonicalCommand,
			MEMBER_STALE_MS: MEMBER_STALE_MS, CLUSTER_TICK_MS: CLUSTER_TICK_MS
		}
	};

})();
