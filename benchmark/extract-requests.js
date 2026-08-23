#!/usr/bin/env node
/**
 * Extract top-level requests from a frontcache requests log into a CSV file with an
 * "url,user_agent" header, RFC 4180 quoted (urls carry commas and both fields carry spaces).
 *
 * The log is written by org.frontcache.reqlog.RequestLogger#logRequest(), one line per
 * request, space separated, with quoted fields for url / client-ip / user-agent:
 *
 *   timestamp request-id domain http-method {success|error} {toplevel|include|include-async} \
 *   {cacheable|direct} {dynamic|from-cache|dynamic-soft} runtime-millis datalength-bytes \
 *   "url" "client-ip" frontcache-id {bot|browser} "user-agent"
 *
 * Only lines whose request-type column is "toplevel" are kept - includes are resolved by
 * the engine itself and would be replayed twice by a benchmark driver.
 *
 * Usage:
 *   node extract-requests.js [options] [logfile]
 *
 *   --out <file>       output file (default: benchmark/requests/requests.csv)
 *   --limit <n>        stop after n urls (default: all)
 *   --unique           dedupe on url, first occurrence wins - the user-agent of that first
 *                      occurrence is the one kept (default: keep duplicates, so the
 *                      output preserves the real request sequence and hit distribution)
 *   --errors           keep hystrix-error lines too (default: success only)
 *   --direct           keep non-cacheable ("direct") lines too (default: cacheable only)
 *   --browsers-only    keep only client-type "browser"
 *   --path-only        write path+query instead of the absolute url
 *   --url-only         write just the url, no user-agent column
 *   --no-header        omit the CSV header row
 *   --stats            print per-column counters to stderr
 */

const fs = require('fs');
const path = require('path');
const readline = require('readline');
const zlib = require('zlib');

const DEFAULT_LOG = path.join(__dirname, '..', 'examples', 'log-analytics', 'logs', 'fc-us.hobbyray.com-frontcache-requests.log');
const DEFAULT_OUT = path.join(__dirname, 'requests', 'requests.csv');

function parseArgs(argv) {
	const opts = {
		log: DEFAULT_LOG,
		out: DEFAULT_OUT,
		limit: Infinity,
		unique: false,
		successOnly: true,
		cacheableOnly: true,
		browsersOnly: false,
		pathOnly: false,
		userAgent: true,
		header: true,
		stats: false,
	};

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		switch (arg) {
			case '--out': opts.out = argv[++i]; break;
			case '--limit': opts.limit = Number(argv[++i]); break;
			case '--unique': opts.unique = true; break;
			case '--errors': opts.successOnly = false; break;
			case '--direct': opts.cacheableOnly = false; break;
			case '--browsers-only': opts.browsersOnly = true; break;
			case '--path-only': opts.pathOnly = true; break;
			case '--url-only': opts.userAgent = false; break;
			case '--no-header': opts.header = false; break;
			case '--stats': opts.stats = true; break;
			case '-h':
			case '--help': usage(); process.exit(0);
			default:
				if (arg.startsWith('-')) { console.error('unknown option: ' + arg); usage(); process.exit(1); }
				opts.log = arg;
		}
	}

	if (!Number.isFinite(opts.limit) && opts.limit !== Infinity) {
		console.error('--limit must be a number');
		process.exit(1);
	}
	return opts;
}

function usage() {
	const header = fs.readFileSync(__filename, 'utf8');
	const doc = header.slice(header.indexOf('/**') + 3, header.indexOf('*/'));
	console.error(doc.replace(/^\s*\* ?/gm, ''));
}

/** Quote a value for CSV (RFC 4180) - only when it has to be quoted. */
function csvField(value) {
	return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

/**
 * Split a log line into fields: runs of non-space characters, except that a field
 * starting with a double quote runs to the next double quote (urls, ips and user
 * agents are quoted and may contain spaces).
 */
function splitFields(line) {
	const fields = [];
	let i = 0;
	const len = line.length;
	while (i < len) {
		while (i < len && line[i] === ' ') i++;
		if (i >= len) break;
		if (line[i] === '"') {
			const end = line.indexOf('"', i + 1);
			if (end === -1) { fields.push(line.slice(i + 1)); break; }
			fields.push(line.slice(i + 1, end));
			i = end + 1;
		} else {
			let end = line.indexOf(' ', i);
			if (end === -1) end = len;
			fields.push(line.slice(i, end));
			i = end;
		}
	}
	return fields;
}

// column indexes after splitFields()
const COL = {
	TIMESTAMP: 0,
	REQUEST_ID: 1,
	DOMAIN: 2,
	METHOD: 3,
	HYSTRIX: 4,     // success | error
	TYPE: 5,        // toplevel | include | include-async
	CACHEABLE: 6,   // cacheable | direct
	CACHED: 7,      // dynamic | from-cache | dynamic-soft
	RUNTIME: 8,
	LENGTH: 9,
	URL: 10,
	CLIENT_IP: 11,
	FC_ID: 12,
	CLIENT_TYPE: 13, // bot | browser
	USER_AGENT: 14,
};

async function main() {
	const opts = parseArgs(process.argv.slice(2));

	if (!fs.existsSync(opts.log)) {
		console.error('log file not found: ' + opts.log);
		process.exit(1);
	}

	fs.mkdirSync(path.dirname(opts.out), { recursive: true });

	let input = fs.createReadStream(opts.log);
	if (opts.log.endsWith('.gz')) input = input.pipe(zlib.createGunzip());

	const rl = readline.createInterface({ input, crlfDelay: Infinity });
	const out = fs.createWriteStream(opts.out);
	const seen = opts.unique ? new Set() : null;

	if (opts.header) out.write(opts.userAgent ? 'url,user_agent\n' : 'url\n');

	const stats = { lines: 0, malformed: 0, toplevel: 0, skippedType: 0, skippedError: 0, skippedDirect: 0, skippedClientType: 0, skippedBadUrl: 0, duplicates: 0, written: 0 };
	const byType = new Map();

	for await (const line of rl) {
		stats.lines++;
		if (!line) continue;

		const f = splitFields(line);
		if (f.length < COL.CLIENT_TYPE + 1) { stats.malformed++; continue; }

		if (opts.stats) byType.set(f[COL.TYPE], (byType.get(f[COL.TYPE]) || 0) + 1);

		if (f[COL.TYPE] !== 'toplevel') { stats.skippedType++; continue; }
		stats.toplevel++;

		if (opts.successOnly && f[COL.HYSTRIX] !== 'success') { stats.skippedError++; continue; }
		if (opts.cacheableOnly && f[COL.CACHEABLE] !== 'cacheable') { stats.skippedDirect++; continue; }
		if (opts.browsersOnly && f[COL.CLIENT_TYPE] !== 'browser') { stats.skippedClientType++; continue; }

		let url = f[COL.URL];
		if (!url || !/^https?:\/\//i.test(url)) { stats.skippedBadUrl++; continue; }

//		if (-1 === url.indexOf('coin_definition')) { stats.skippedBadUrl++; continue; }

		if (opts.pathOnly) {
			try {
				const u = new URL(url);
				url = u.pathname + u.search;
			} catch (e) {
				stats.skippedBadUrl++;
				continue;
			}
		}

		if (seen) {
			if (seen.has(url)) { stats.duplicates++; continue; }
			seen.add(url);
		}

		let line2write = csvField(url);
		if (opts.userAgent) {
			// the logger writes the raw User-Agent header, which is "null" when absent; keep the
			// column present either way and flatten any newline so one request stays one CSV row
			const ua = (f[COL.USER_AGENT] || '').replace(/[\r\n]+/g, ' ').trim();
			line2write += ',' + csvField(ua && ua !== 'null' ? ua : '-');
		}

		if (!out.write(line2write + '\n')) await new Promise(r => out.once('drain', r));
		stats.written++;

		if (stats.written >= opts.limit) { rl.close(); break; }
	}

	await new Promise((resolve, reject) => out.end(err => (err ? reject(err) : resolve())));

	console.error(`read ${stats.lines} lines, ${stats.toplevel} toplevel, wrote ${stats.written} requests -> ${opts.out}`);
	if (opts.stats) {
		console.error('request types: ' + [...byType].map(([k, v]) => `${k}=${v}`).join(' '));
		console.error('skipped: ' + Object.entries(stats).filter(([k]) => k.startsWith('skipped') || k === 'duplicates' || k === 'malformed').map(([k, v]) => `${k}=${v}`).join(' '));
	}
}

main().catch(err => { console.error(err); process.exit(1); });
