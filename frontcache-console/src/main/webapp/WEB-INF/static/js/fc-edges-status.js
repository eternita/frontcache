/**
 * Live numbers for the "Edges status" block.
 *
 * Polls /edges-status.json and writes into the rows the JSP already rendered. No jQuery, no
 * dependencies - the same house rule as fc-dashboard.js.
 *
 * Why a poll and not the dashboard SSE stream, which this page already holds open per edge: that
 * stream's JSON is a pinned wire contract read by external Turbine across a mixed fleet, it ticks at
 * 500ms because circuit metrics move that fast, and it only exists on this one page - the same block
 * renders on the cache and config pages. One poll serves all of it. See
 * docs/archive/edges-status-realtime-proposal.md section 4.
 *
 * Formatting lives here and only here. The JSP renders placeholders rather than formatted values, so
 * there is one implementation of "what does 4402341888 look like" rather than one in Java for the
 * first paint and one here for every paint after it.
 */
var FcEdgesStatus = (function () {

	"use strict";

	/** The node reports -1 for a number it does not track. Not zero - see CacheStats. */
	var UNKNOWN = -1;

	var EM_DASH = "—";

	var DEFAULT_INTERVAL_MS = 10000;

	function isKnown(value) {
		return typeof value === "number" && value >= 0;
	}

	/**
	 * The session expired: hand the browser to the login page, remembering where it was.
	 *
	 * Deliberately not shared with fc-dashboard.js, which needs the same three lines: these two
	 * modules are dependency-free and load on different sets of pages, and a shared helper would
	 * make one of them depend on the other's script tag being present.
	 */
	function goToLogin() {
		// The LAST path segment plus the query, not the whole pathname: every console URL is one
		// segment under the context root, and LoginController resolves "next" relative to the
		// context - so a console deployed at /console would otherwise come back to
		// /console/console/cache-view. Both URLs here are relative for the same reason, which is
		// the convention the JSPs already follow ("realtime", "edges-status.json").
		var path = window.location.pathname;
		var here = path.substring(path.lastIndexOf("/")) + window.location.search;

		window.location.replace("login?next=" + encodeURIComponent(here));
	}

	// ---- formatting ------------------------------------------------------------------------------

	function count(value) {
		return isKnown(value) ? value.toLocaleString("en-US") : EM_DASH;
	}

	/**
	 * Binary units, one decimal. Fixed to en-US rather than the viewer's locale: the console is read
	 * over shoulders and pasted into tickets, and a decimal comma in one screenshot and a decimal point
	 * in the next is a support conversation nobody needs.
	 */
	function bytes(value) {
		if (!isKnown(value))
			return EM_DASH;

		if (value < 1024)
			return value + " B";

		var units = ["KB", "MB", "GB", "TB", "PB"];
		var size = value / 1024;
		var unit = 0;

		while (size >= 1024 && unit < units.length - 1) {
			size = size / 1024;
			unit++;
		}

		return size.toFixed(1) + " " + units[unit];
	}

	function percent(load) {
		return isKnown(load) ? (load * 100).toFixed(1) + " %" : EM_DASH;
	}

	/** Coarse on purpose: "up 11d 4h" answers the question, "up 11d 4h 13m 6s" makes it a puzzle. */
	function uptime(millis) {
		if (!isKnown(millis))
			return "";

		var seconds = Math.floor(millis / 1000);
		var days = Math.floor(seconds / 86400);
		var hours = Math.floor((seconds % 86400) / 3600);
		var minutes = Math.floor((seconds % 3600) / 60);

		if (days > 0)
			return "up " + days + "d " + hours + "h";
		if (hours > 0)
			return "up " + hours + "h " + minutes + "m";
		if (minutes > 0)
			return "up " + minutes + "m";

		return "up " + seconds + "s";
	}

	function pair(left, right) {
		if (left === EM_DASH && right === EM_DASH)
			return EM_DASH;

		return left + " / " + right;
	}

	// ---- rendering -------------------------------------------------------------------------------

	function set(row, field, text) {
		var node = row.querySelector("[data-field='" + field + "']");
		if (node)
			node.textContent = text;
	}

	function render(row, edge) {

		set(row, "uptime", uptime(edge.uptimeMillis));
		set(row, "cachedEntries", count(edge.cachedAmount));
		set(row, "cacheSize", bytes(edge.l2DiskBytes));
		set(row, "l1", pair(count(edge.l1Entries) + (isKnown(edge.l1Entries) ? " items" : ""), bytes(edge.l1ContentBytes)));
		set(row, "l2", pair(count(edge.l2Entries) + (isKnown(edge.l2Entries) ? " items" : ""), bytes(edge.l2DiskBytes)));
		set(row, "heap", pair(bytes(edge.heapUsedBytes), bytes(edge.heapMaxBytes)));
		set(row, "cpu", percent(edge.cpuProcessLoad));

		var offline = row.querySelector("[data-field='offline']");
		if (offline)
			offline.style.display = edge.available ? "none" : "";

		// The dot starts PENDING: the page is rendered from the console's own edge list without
		// contacting anything, so until this first poll answers there is genuinely no online status to
		// show. Rendering that as red would report every healthy node as down for a moment on every
		// page load; rendering it as green would hide a node that is actually gone.
		var dot = row.querySelector("[data-field='dot']");
		if (dot) {
			var state = edge.available ? "fc-dot-online" : "fc-dot-offline";
			dot.className = "fc-dot " + state;
			dot.title = edge.available ? "online" : "not reachable";
		}
	}

	// ---- the poll --------------------------------------------------------------------------------

	function Poller(root, url, intervalMs) {
		this.root = root;
		this.url = url;
		this.intervalMs = intervalMs || DEFAULT_INTERVAL_MS;
		this.timer = null;
		this.inFlight = false;
	}

	Poller.prototype.rowFor = function (edgeUrl) {
		return this.root.querySelector("[data-edge-url='" + (edgeUrl || "").replace(/'/g, "\\'") + "']");
	};

	Poller.prototype.apply = function (edges) {

		var self = this;

		edges.forEach(function (edge) {
			var row = self.rowFor(edge.url);
			if (!row)
				return;   // an edge added to the console since this page was rendered

			render(row, edge);
		});
	};

	/**
	 * @param force  poll even when the tab is hidden. Used for the first poll only - see start().
	 */
	Poller.prototype.poll = function (force) {

		var self = this;

		// A hidden tab has nobody reading it, so the RECURRING poll skips. Skipping the fetch rather
		// than stopping the timer keeps this a one-liner and means the panel refreshes on its own once
		// the tab comes back.
		if ((document.hidden && !force) || this.inFlight)
			return;

		this.inFlight = true;

		fetch(this.url, { credentials: "same-origin", headers: { "Accept": "application/json" } })
			.then(function (response) {
				// The session expired while this page was open. Without this the panel just goes
				// quietly stale - "the numbers stopped updating" - which gets diagnosed as a broken
				// edge rather than as a sign-out. See docs/console-login-ui.md section 9.
				if (401 === response.status)
					return goToLogin();
				if (!response.ok)
					throw new Error("HTTP " + response.status);
				return response.json();
			})
			.then(function (edges) {
				// undefined is the sign-out branch above having taken over the page
				if (edges)
					self.apply(edges);
			})
			.catch(function (e) {
				// A failed poll is not worth disturbing the panel over: the numbers on screen are the
				// last ones that were true, and the next tick is ten seconds away. Losing the console
				// itself is what the browser's own error surface is for.
				console.warn("Could not refresh the edge status", e);
			})
			.then(function () {
				self.inFlight = false;
			});
	};

	Poller.prototype.start = function () {
		var self = this;

		// The first poll ignores visibility. A page opened in a background tab - a restored session, a
		// middle-click - would otherwise sit on a column of em dashes until someone focused it, which
		// reads as "this node reports nothing" rather than "nobody has looked yet". One request per
		// page load is what the server-rendered block cost anyway.
		this.poll(true);
		this.timer = setInterval(function () { self.poll(); }, this.intervalMs);

		// The tab coming back should not wait out the rest of an interval to show live numbers.
		document.addEventListener("visibilitychange", function () {
			if (!document.hidden)
				self.poll();
		});
	};

	return {
		/**
		 * @param root        the element holding the [data-edge-url] rows
		 * @param url         the JSON endpoint
		 * @param intervalMs  poll interval, default 10s
		 */
		attach: function (root, url, intervalMs) {
			if (!root)
				return null;

			var poller = new Poller(root, url, intervalMs);
			poller.start();
			return poller;
		},

		// exposed for tests
		_format: { count: count, bytes: bytes, percent: percent, uptime: uptime, pair: pair }
	};

})();
