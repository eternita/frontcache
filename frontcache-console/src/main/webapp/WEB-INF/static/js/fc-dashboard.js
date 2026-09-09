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

	// ---- cluster request rate ------------------------------------------------------------------
	//
	// One number per command: what the whole cluster is taking, shown on every member's own card
	// beside that member's rate. The page already holds an open EventSource per edge, so this costs
	// no connection and no request to the fleet - the frames are here.
	// See docs/circuit-cluster-proposal.md.

	/**
	 * How long a member's last frame for a command still counts.
	 *
	 * Ten node ticks (the node publishes every 500 ms - FcSampleSseServlet's
	 * DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS). Without an expiry a node that goes away leaves its
	 * last frame in the cluster total for as long as the page is open, so every surviving member's
	 * card would keep claiming traffic from a dead region. With it the cluster figure FALLS when a
	 * member drops out, which is the correct answer and is what that member's own panel switching to
	 * "retrying ..." explains.
	 */
	var MEMBER_STALE_MS = 5000;

	/**
	 * Pre-2.9.0 command key -> the key that replaced it. Mirrors FcCommandKeys.LEGACY_COMMAND_KEYS.
	 *
	 * The console reads a MIXED fleet, so mid-upgrade one member emits Input-Request and another
	 * Input-Requests. Without canonicalizing, those are two separate cluster totals and each member's
	 * card shows a figure covering only the half of the fleet that spells the command its way.
	 *
	 * Cache-Miss is two legacy keys folding into ONE, and on a pre-2.9.0 filter-mode node both are
	 * live at once (the filter chain serves the page's origin call, HTTP serves its includes) - so one
	 * member can contribute two frames to one command, and for a request rate both of them count.
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

	/**
	 * The request rate of a group of edges, per command.
	 *
	 * Members' frames are simply added up, and the reason that is sound is worth being explicit
	 * about: requestCount is a windowed count over the node's own rolling window (10 s by default),
	 * not a delta since its last frame. So adding frames that arrived at different moments
	 * double-counts nothing, and the most a member can contribute is one 500 ms tick of staleness
	 * inside a twenty-tick window.
	 *
	 * Each member is converted to a rate against ITS OWN window before being added, so a fleet whose
	 * nodes disagree on resilience.command.<key>.metrics.rollingStats.timeInMilliseconds still gets
	 * the right answer - there is no shared divisor to get wrong.
	 */
	function ClusterRates(name, memberUrls) {

		this.name = name;
		this.members = memberUrls;

		// canonical command -> contribution key -> {rate, at}
		this.contributions = {};

		this.listeners = [];
	}

	/**
	 * Called whenever any member reports, so every member's panel shows the same total.
	 *
	 * Without this each card only refreshed its cluster line when its OWN node's frame arrived, and
	 * since the members publish on their own clocks the three panels showed three figures for the one
	 * quantity - a couple of percent apart, which reads as an arithmetic bug rather than as the
	 * half-second of skew it is.
	 */
	ClusterRates.prototype.subscribe = function (listener) {
		this.listeners.push(listener);
	};

	ClusterRates.prototype.publish = function () {
		this.listeners.forEach(function (listener) { listener(); });
	};

	ClusterRates.prototype.accept = function (memberUrl, frame) {

		var command = canonicalCommand(frame.name);
		var count = num(frame, "requestCount");

		if (count === null)
			return;

		if (!this.contributions[command])
			this.contributions[command] = {};

		// Keyed by member AND by the key as it arrived, so a pre-2.9.0 filter node's Cache-Origin-http
		// and Cache-Origin-filter are two contributions to Cache-Miss rather than one overwriting the
		// other.
		this.contributions[command][memberUrl + " " + frame.name] = {
			rate: Math.max(0, count) / ((num(frame, WINDOW_KEY) || DEFAULT_WINDOW_MS) / 1000),
			at: Date.now()
		};

		this.publish();
	};

	ClusterRates.prototype.drop = function (memberUrl, commandName) {

		var contributions = this.contributions[canonicalCommand(commandName)];

		if (contributions) {
			delete contributions[memberUrl + " " + commandName];
			this.publish();
		}
	};

	/**
	 * The cluster's rate for one command, or null when nothing live reports it.
	 *
	 * Stale contributions are pruned here rather than on a timer: this is called as each frame
	 * arrives, which is twice a second per member, so there is nothing a timer would notice sooner.
	 */
	ClusterRates.prototype.ratePerSecond = function (commandName) {

		var contributions = this.contributions[canonicalCommand(commandName)];

		if (!contributions)
			return null;

		var now = Date.now();
		var total = null;

		for (var key in contributions)
			if (contributions.hasOwnProperty(key)) {
				if (now - contributions[key].at > MEMBER_STALE_MS)
					delete contributions[key];
				else
					total = (total || 0) + contributions[key].rate;
			}

		return total;
	};

	/** How many members are currently counted towards a command - for the line's tooltip. */
	ClusterRates.prototype.reporting = function (commandName) {

		var contributions = this.contributions[canonicalCommand(commandName)];

		if (!contributions)
			return 0;

		var now = Date.now();
		var members = {};

		for (var key in contributions)
			if (contributions.hasOwnProperty(key) && now - contributions[key].at <= MEMBER_STALE_MS)
				members[key.substring(0, key.indexOf(" "))] = true;

		return Object.keys(members).length;
	};


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

	/**
	 * @param cluster the ClusterRates this edge belongs to, or null. The rate is read here rather
	 *                than passed in as a number so the line and its tooltip agree on how many
	 *                members are being counted.
	 */
	Circuit.prototype.update = function (frame, cluster) {

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

		// The old "Cluster:" line divided THIS frame by the same reportingHosts the Host line used, so
		// on a node's own frame - reportingHosts:1, which is every frame a node sends - it printed the
		// Host number a second time. It is now rendered only where there is a cluster to add up, and
		// it is the sum of the members' own rates rather than anything derived from this one frame.
		var clusterRate = cluster ? cluster.ratePerSecond(frame.name) : null;

		// Rebuilt with the rest of the card, so the handle setClusterRate writes through is replaced
		// here on every frame rather than kept across renders.
		self.clusterValue = null;

		if (clusterRate !== null) {
			var line = clusterRateLine(clusterRate, cluster, frame.name);
			self.clusterValue = line.value;
			self.data.appendChild(line.node);
		}

		self.data.appendChild(circuitStatus(frame, reportingHosts));
		self.data.appendChild(latencyTable(frame, reportingHosts));

		self.bubble.setAttribute("r", String(bubbleRadius(ratePerSecondPerHost)));
		self.bubble.setAttribute("cx", bubbleCentre(ratePerSecondPerHost));
		self.bubble.setAttribute("cy", bubbleCentre(ratePerSecondPerHost));
		self.bubble.setAttribute("fill", circuitColor(errorPercentage));

		self.pushPoint(ratePerSecond);
	};

	/**
	 * Writes a new cluster total into a card that already has the line, between full renders.
	 *
	 * Only the number is touched - not the card, not its own rate, not the sparkline, which stay on
	 * this node's own 500 ms frame. A null (every member of the cluster now silent) is left alone: the
	 * line goes when the card is next drawn, which is at most one tick away.
	 */
	Circuit.prototype.setClusterRate = function (value) {

		if (this.clusterValue && value !== null)
			this.clusterValue.textContent = round1(value);
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

	/**
	 * The cluster total, on a member's own card - the same shape as the Host line above it, since it
	 * is the same quantity over more nodes.
	 *
	 * Returns the value span alongside the line, rather than leaving the caller to find it by child
	 * position: setClusterRate writes through that reference twice a second per member, and "the
	 * second child" would break silently the first time anything is added to the line.
	 */
	function clusterRateLine(value, cluster, commandName) {

		var node = el("div", "fc-rate fc-cluster-rate");

		node.title = "Total request rate per second across cluster \u201c" + cluster.name + "\u201d - "
				+ cluster.reporting(commandName) + " of " + cluster.members.length
				+ " node(s) currently reporting " + canonicalCommand(commandName) + ":\n"
				+ cluster.members.map(hostOf).join("\n");

		var valueNode = el("span", "fc-value", round1(value));

		node.appendChild(el("span", "fc-smaller", "Cluster: "));
		node.appendChild(valueNode);
		node.appendChild(document.createTextNode("/s"));

		return { node: node, value: valueNode };
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

	// ---- the row of cards ----------------------------------------------------------------------

	/**
	 * A row of circuit cards, keyed by command name. Owns the cards' lifecycle and nothing else.
	 *
	 * @param cluster the ClusterRates the panel's edge belongs to, or null - handed to every card so
	 *                it can show what the whole cluster is taking beside what this node is taking.
	 */
	function CircuitBoard(cluster) {

		var self = this;

		self.circuits = {};
		self.cluster = cluster || null;
		self.node = el("div", "fc-circuits");

		if (self.cluster)
			self.cluster.subscribe(function () { self.refreshClusterRates(); });
	}

	/** Any member reported: re-read the totals this row is showing. */
	CircuitBoard.prototype.refreshClusterRates = function () {

		var self = this;

		for (var commandName in self.circuits)
			if (self.circuits.hasOwnProperty(commandName))
				self.circuits[commandName].setClusterRate(self.cluster.ratePerSecond(commandName));
	};

	CircuitBoard.prototype.update = function (commandName, frame) {

		var circuit = this.circuits[commandName];

		if (!circuit) {
			circuit = new Circuit(commandName);
			this.circuits[commandName] = circuit;
			this.insertSorted(circuit);
		}

		circuit.update(frame, this.cluster);
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
	 * @param cluster the ClusterRates shared with this edge's peers, or null. Every frame is rendered
	 *                on this panel AND added to that - one EventSource either way, since the frames a
	 *                cluster total needs are the ones this panel is already receiving.
	 */
	function StreamPanel(container, stream, cluster) {

		var self = this;

		self.url = stream.url;
		self.cluster = cluster || null;
		self.board = new CircuitBoard(self.cluster);

		self.root = el("div", "fc-dashboard");
		self.root.appendChild(legendNode());

		var name = el("div", "fc-stream-name", stream.name || stream.url);

		// On the hostname's own line rather than under it: it qualifies the hostname ("this node, as
		// part of that group"), and a line of its own read as a heading for the cards below it.
		if (self.cluster) {
			var note = el("span", "fc-cluster-member",
					" in cluster \u201c" + self.cluster.name + "\u201d, "
					+ self.cluster.members.length + " nodes");
			note.title = self.cluster.members.map(hostOf).join("\n");
			name.appendChild(note);
		}

		self.root.appendChild(name);

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
			if (self.cluster)
				self.cluster.drop(self.url, frame.name);
			return;
		}

		// The cluster is fed BEFORE the card is drawn, so the line this node's own card shows includes
		// this node's own contribution. The other order lags one frame behind, per member, forever.
		if (self.cluster)
			self.cluster.accept(self.url, frame);

		self.board.update(frame.name, frame);
	};

	/**
	 * An edge url's host AND port, for the cluster tooltip - the full urls would be unreadable.
	 *
	 * The port stays. Dropping it would be FrontCacheClient.getName(), which strips the scheme and
	 * everything from the first colon, so localhost:8080 and localhost:9080 - the e2e topology - come
	 * out as one name; a tooltip listing the same node twice is the mildest form of that bug.
	 */
	function hostOf(edgeUrl) {
		return String(edgeUrl).replace(/^[a-z]+:\/\//i, "").replace(/\/.*$/, "");
	}


	// ---- entry point ---------------------------------------------------------------------------

	return {
		/**
		 * @param container  the element to render into
		 * @param streams    [{name, url, cluster}] - one per edge, from the console's edge list.
		 *                   cluster is null for an edge in none, which is every edge on a console
		 *                   config that predates frontcache.console.clusters.
		 *
		 * One panel per edge, in the console's edge order, exactly as before - a cluster adds a line
		 * to its members' cards and no panel of its own. Members of one cluster share a ClusterRates,
		 * which is the only thing on the page that knows two edges belong together.
		 */
		attach: function (container, streams) {

			if (!streams || !streams.length) {
				container.appendChild(el("span", "fc-status", "No edges configured."));
				return;
			}

			var clusters = {};

			streams.forEach(function (stream) {
				if (!stream.cluster)
					return;
				if (!clusters[stream.cluster])
					clusters[stream.cluster] = [];
				clusters[stream.cluster].push(stream.url);
			});

			for (var name in clusters)
				if (clusters.hasOwnProperty(name))
					clusters[name] = new ClusterRates(name, clusters[name]);

			streams.forEach(function (stream) {
				new StreamPanel(container, stream, stream.cluster ? clusters[stream.cluster] : null);
			});
		},

		// exposed for the console's own tests, not for page code
		_internals: {
			num: num, ramp: ramp, bubbleRadius: bubbleRadius, addCommas: addCommas,
			ClusterRates: ClusterRates, canonicalCommand: canonicalCommand,
			MEMBER_STALE_MS: MEMBER_STALE_MS
		}
	};

})();
