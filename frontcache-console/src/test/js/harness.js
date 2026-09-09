/*
 * Loads fc-dashboard.js outside a browser so its pure functions can be tested.
 *
 * The file is a browser script, not a module: one IIFE assigned to a global `var FcDashboard`. So it
 * is read and evaluated with the two globals it touches stubbed - `window` (console, for warnOnce)
 * and `document` (never reached by anything tested here; mergeFrames builds no DOM, which is the
 * point of keeping it a pure function).
 */
const fs = require("node:fs");
const path = require("node:path");

const SCRIPT = path.join(__dirname, "..", "..", "main", "webapp", "WEB-INF", "static", "js", "fc-dashboard.js");

const warnings = [];

const stubElement = () => ({
	appendChild() {}, setAttribute() {}, style: { setProperty() {} }, children: [], className: "", textContent: ""
});

// warnOnce guards on window.console and then calls the global console.warn - the same object in a
// browser. Capture it there.
global.window = { console: console, setInterval: () => 0 };

const realWarn = console.warn;
console.warn = (message) => { warnings.push(String(message)); };
void realWarn;
global.document = {
	createElement: stubElement,
	createElementNS: stubElement,
	createTextNode: () => ({})
};

eval(fs.readFileSync(SCRIPT, "utf8"));

module.exports = { internals: FcDashboard._internals, warnings };
