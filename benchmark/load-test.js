#!/usr/bin/env node
/**
 * Replay recorded frontcache requests as a load test.
 *
 * Reads benchmark/requests/requests.csv (produced by extract-requests.js: an "url,user_agent"
 * CSV) and replays every row against a target frontcache node. X workers run in parallel and
 * each worker runs its own requests sequentially - one in flight per worker, so --threads is
 * the exact concurrency level. Workers pull from a single shared cursor over the file, so a
 * slow request never leaves a worker's share of the file stranded.
 *
 * ("threads" in the node sense: these are async workers on the event loop, not worker_threads.
 * Replaying HTTP is IO bound - the load generator never becomes the bottleneck - and one socket
 * per worker gives the same concurrency an OS thread would, without the copying.)
 *
 * The recorded urls are absolute production urls, so by default they are re-pointed at
 * --target and the original host is preserved in the Host header - that is what frontcache
 * routes its per-domain config on. Nothing hits the recorded host unless you ask for it
 * with --as-is.
 *
 * Usage:
 *   node load-test.js [options]
 *
 *   --threads <n>      parallel workers, sequential within each (default: 10)
 *   --target <url>     replay against this origin (default: http://localhost:9080)
 *   --as-is            request the recorded absolute urls verbatim, ignoring --target
 *   --requests <file>  input CSV (default: benchmark/requests/requests.csv)
 *   --limit <n>        replay at most n requests per pass (default: the whole file)
 *   --skip <n>         skip the first n rows (default: 0)
 *   --loop <n>         replay that set n times, so n x --limit requests in total (default: 1)
 *   --timeout <ms>     per-request timeout (default: 30000)
 *   --gzip             send "accept-encoding: gzip" (default: identity, so bytes are raw)
 *   --no-user-agent    do not replay the recorded user-agent
 *   --quiet            no live progress line
 *   --csv <file>       also write a per-request csv: url,status,ms,bytes,cache
 *
 * Every run is saved to benchmark/results/<date>_<time>.txt (the same report that is printed).
 *   --results <dir>    directory for saved reports (default: benchmark/results)
 *   --name <label>     appended to the file name, e.g. 2026-08-22_14-40-33_warm-cache.txt
 *   --no-save          print the report only, do not write a file
 */

const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');
const readline = require('readline');
const { URL } = require('url');

const DEFAULT_REQUESTS = path.join(__dirname, 'requests', 'requests.csv');
const DEFAULT_TARGET = 'http://localhost:9080';
const DEFAULT_RESULTS = path.join(__dirname, 'results');
const PROGRESS_INTERVAL_MS = 1000;

function parseArgs(argv) {
	const opts = {
		threads: 10,
		target: DEFAULT_TARGET,
		asIs: false,
		requests: DEFAULT_REQUESTS,
		limit: Infinity,
		skip: 0,
		loop: 1,
		timeout: 30000,
		gzip: false,
		userAgent: true,
		quiet: false,
		csv: null,
		results: DEFAULT_RESULTS,
		save: true,
		name: null,
	};

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		switch (arg) {
			case '--threads': case '-t': opts.threads = num(argv[++i], '--threads'); break;
			case '--target': opts.target = argv[++i]; break;
			case '--as-is': opts.asIs = true; break;
			case '--requests': opts.requests = argv[++i]; break;
			case '--limit': case '-n': opts.limit = num(argv[++i], '--limit'); break;
			case '--skip': opts.skip = num(argv[++i], '--skip'); break;
			case '--loop': opts.loop = num(argv[++i], '--loop'); break;
			case '--timeout': opts.timeout = num(argv[++i], '--timeout'); break;
			case '--gzip': opts.gzip = true; break;
			case '--no-user-agent': opts.userAgent = false; break;
			case '--quiet': case '-q': opts.quiet = true; break;
			case '--csv': opts.csv = argv[++i]; break;
			case '--results': opts.results = argv[++i]; break;
			case '--name': opts.name = argv[++i]; break;
			case '--no-save': opts.save = false; break;
			case '-h': case '--help': usage(); process.exit(0);
			default: console.error('unknown option: ' + arg); usage(); process.exit(1);
		}
	}

	if (opts.threads < 1) { console.error('--threads must be >= 1'); process.exit(1); }
	return opts;
}

function num(raw, name) {
	const n = Number(raw);
	if (!Number.isFinite(n)) { console.error(name + ' must be a number'); process.exit(1); }
	return n;
}

function usage() {
	const src = fs.readFileSync(__filename, 'utf8');
	console.error(src.slice(src.indexOf('/**') + 3, src.indexOf('*/')).replace(/^\s*\* ?/gm, ''));
}

/** Parse one RFC 4180 csv row. Rows are newline-free by construction (see extract-requests.js). */
function parseCsvLine(line) {
	const fields = [];
	let i = 0;
	while (i <= line.length) {
		if (line[i] === '"') {
			let value = '';
			i++;
			while (i < line.length) {
				if (line[i] === '"') {
					if (line[i + 1] === '"') { value += '"'; i += 2; continue; }
					i++; break;
				}
				value += line[i++];
			}
			fields.push(value);
			i++; // consume the comma
		} else {
			let end = line.indexOf(',', i);
			if (end === -1) end = line.length;
			fields.push(line.slice(i, end));
			i = end + 1;
		}
		if (i > line.length) break;
	}
	return fields;
}

/**
 * Pull-based reader over the csv: workers await next(), the underlying stream is paused
 * whenever the buffer is full, so the 170MB file is never held in memory.
 */
class RequestSource {
	constructor(opts) {
		this.opts = opts;
		this.buffer = [];
		this.highWater = Math.max(1000, opts.threads * 100);
		this.waiters = [];
		this.done = false;         // no more requests will ever be produced
		this.readerClosed = true;  // no live readline right now (between passes / after the end)
		this.total = 0;            // requests handed out across all passes
		this.servedThisPass = 0;
		this.skipped = 0;
		this.pass = 1;
		this.headerSeen = false;
	}

	start() {
		// destroying the stream does not discard the lines readline has already buffered, and the
		// old listeners still point at this object - so each pass carries its own token and any
		// event arriving from a superseded reader is dropped instead of bleeding into the next pass
		const passNo = this.pass;
		const stale = () => this.pass !== passNo;

		const stream = fs.createReadStream(this.opts.requests);
		stream.on('error', err => { console.error(String(err)); process.exit(1); });
		const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });
		this.rl = rl;
		this.stream = stream;
		this.readerClosed = false;
		this.headerSeen = false;
		this.servedThisPass = 0;
		this.skipped = 0;

		rl.on('line', line => {
			if (stale() || this.readerClosed || !line) return;

			if (!this.headerSeen) {
				this.headerSeen = true;
				if (line.startsWith('url,') || line === 'url') return; // header row
			}

			if (this.skipped < this.opts.skip) { this.skipped++; return; }
			if (this.servedThisPass >= this.opts.limit) { this.closeReader(); return; }

			const [url, userAgent] = parseCsvLine(line);
			if (!url) return;
			this.servedThisPass++;
			this.total++;
			this.push({ url, userAgent: userAgent || null });
		});

		// a pass ends at eof or at --limit; --loop replays the same set from the top
		rl.on('close', () => {
			if (stale()) return;
			this.readerClosed = true;
			if (this.done) return;
			if (this.pass < this.opts.loop && this.servedThisPass > 0) {
				this.pass++;
				this.start();
				return;
			}
			this.finish();
		});
	}

	push(item) {
		const waiter = this.waiters.shift();
		if (waiter) { waiter(item); return; }
		this.buffer.push(item);
		if (this.buffer.length >= this.highWater) this.pauseReader();
	}

	// pausing/resuming a closed readline throws ERR_USE_AFTER_CLOSE, so both are no-ops
	// whenever there is no live reader (between --loop passes, or after the last one)
	pauseReader() { if (!this.readerClosed) this.rl.pause(); }

	resumeReader() { if (!this.readerClosed) this.rl.resume(); }

	closeReader() {
		if (this.readerClosed) return;
		this.readerClosed = true;
		// readline emits 'close' synchronously, and that handler starts the next --loop pass,
		// which reassigns this.rl / this.stream - so grab the current pair first, or the tear
		// down lands on the reader that was just opened
		const rl = this.rl, stream = this.stream;
		stream.destroy();
		rl.close();
	}

	/** No further passes - wake anyone still waiting so the workers can exit. */
	finish() {
		if (this.done) return;
		this.done = true;
		this.closeReader();
		while (this.waiters.length) this.waiters.shift()(null);
	}

	next() {
		if (this.buffer.length) {
			const item = this.buffer.shift();
			if (this.buffer.length < this.highWater / 2) this.resumeReader();
			return Promise.resolve(item);
		}
		if (this.done) return Promise.resolve(null);
		this.resumeReader();
		return new Promise(resolve => this.waiters.push(resolve));
	}
}

/** Where a request goes, and what Host it claims - see the --target note in the header. */
function resolveTarget(rawUrl, opts, targetUrl) {
	const recorded = new URL(rawUrl);
	if (opts.asIs) return { url: recorded, host: recorded.host };
	const url = new URL(recorded.pathname + recorded.search, targetUrl);
	return { url, host: recorded.host };
}

function doRequest(item, opts, targetUrl, agents) {
	return new Promise(resolve => {
		let target;
		try {
			target = resolveTarget(item.url, opts, targetUrl);
		} catch (e) {
			resolve({ ok: false, status: 0, ms: 0, bytes: 0, error: 'bad-url', cache: null });
			return;
		}

		const isHttps = target.url.protocol === 'https:';
		const transport = isHttps ? https : http;
		const headers = { host: target.host, 'accept-encoding': opts.gzip ? 'gzip' : 'identity' };
		if (opts.userAgent && item.userAgent && item.userAgent !== '-') headers['user-agent'] = item.userAgent;

		const started = process.hrtime.bigint();
		const finish = result => {
			const ms = Number(process.hrtime.bigint() - started) / 1e6;
			resolve({ ms, ...result });
		};

		const req = transport.request({
			protocol: target.url.protocol,
			hostname: target.url.hostname,
			port: target.url.port || (isHttps ? 443 : 80),
			path: target.url.pathname + target.url.search,
			method: 'GET',
			headers,
			agent: isHttps ? agents.https : agents.http,
			setHost: false,
		}, res => {
			let bytes = 0;
			res.on('data', chunk => { bytes += chunk.length; });
			res.on('end', () => finish({
				ok: res.statusCode >= 200 && res.statusCode < 400,
				status: res.statusCode,
				bytes,
				error: null,
				cache: cacheDisposition(res.headers),
			}));
			res.on('error', err => finish({ ok: false, status: res.statusCode || 0, bytes, error: err.code || err.message, cache: null }));
		});

		req.setTimeout(opts.timeout, () => req.destroy(new Error('timeout')));
		req.on('error', err => finish({ ok: false, status: 0, bytes: 0, error: err.message === 'timeout' ? 'timeout' : (err.code || err.message), cache: null }));
		req.end();
	});
}

/**
 * from-cache | dynamic | dynamic-soft, read off the toplevel x-frontcache-trace-request.N
 * header. Only present when the node runs with front-cache.log-to-headers=true; null otherwise.
 */
function cacheDisposition(headers) {
	for (const name of Object.keys(headers)) {
		if (!name.startsWith('x-frontcache-trace-request')) continue;
		const parts = String(headers[name]).split(' ');
		if (parts[1] === 'toplevel') return parts[2];
	}
	return null;
}

function percentile(sorted, p) {
	if (!sorted.length) return 0;
	const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
	return sorted[idx];
}

function formatDuration(ms) {
	const totalSeconds = ms / 1000;
	if (totalSeconds < 60) return `${totalSeconds.toFixed(2)}s`;
	const h = Math.floor(totalSeconds / 3600);
	const m = Math.floor((totalSeconds % 3600) / 60);
	const s = totalSeconds % 60;
	return (h ? `${h}h ` : '') + `${m}m ${s.toFixed(1)}s (${totalSeconds.toFixed(1)}s)`;
}

/** Local-time file stamp: 2026-08-22_14-40-33 - sorts chronologically as plain text. */
function fileStamp(date) {
	const p2 = n => String(n).padStart(2, '0');
	return `${date.getFullYear()}-${p2(date.getMonth() + 1)}-${p2(date.getDate())}` +
		`_${p2(date.getHours())}-${p2(date.getMinutes())}-${p2(date.getSeconds())}`;
}

function slug(text) {
	return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

function formatBytes(bytes) {
	const units = ['B', 'KB', 'MB', 'GB', 'TB'];
	let value = bytes, i = 0;
	while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
	return `${value.toFixed(i ? 2 : 0)} ${units[i]}`;
}

async function main() {
	const opts = parseArgs(process.argv.slice(2));

	if (!fs.existsSync(opts.requests)) {
		console.error('requests file not found: ' + opts.requests);
		console.error('generate it first: node extract-requests.js');
		process.exit(1);
	}

	let targetUrl = null;
	if (!opts.asIs) {
		try { targetUrl = new URL(opts.target); }
		catch (e) { console.error('--target is not a valid url: ' + opts.target); process.exit(1); }
	}

	const agentOpts = { keepAlive: true, maxSockets: opts.threads, maxFreeSockets: opts.threads, timeout: opts.timeout };
	const agents = { http: new http.Agent(agentOpts), https: new https.Agent({ ...agentOpts, rejectUnauthorized: false }) };

	const stats = {
		sent: 0, ok: 0, failed: 0, bytes: 0,
		status: new Map(), errors: new Map(), cache: new Map(),
		durations: [],
	};
	const csvOut = opts.csv ? fs.createWriteStream(opts.csv) : null;
	if (csvOut) csvOut.write('url,status,ms,bytes,cache\n');

	const source = new RequestSource(opts);
	source.start();

	console.error(`replaying ${opts.requests}`);
	console.error(`target ${opts.asIs ? '(as recorded)' : opts.target} | threads ${opts.threads}` +
		(opts.limit === Infinity ? '' : ` | limit ${opts.limit}`) + (opts.loop > 1 ? ` | loop ${opts.loop}` : ''));

	let stopping = false;
	process.on('SIGINT', () => {
		if (stopping) process.exit(130);
		stopping = true;
		console.error('\ninterrupted - draining in-flight requests, report follows');
		source.finish();
	});

	const startedAt = process.hrtime.bigint();
	const startedWall = new Date();

	let progressTimer = null;
	if (!opts.quiet && process.stderr.isTTY) {
		let lastSent = 0, lastAt = startedAt;
		progressTimer = setInterval(() => {
			const now = process.hrtime.bigint();
			const windowSec = Number(now - lastAt) / 1e9;
			const rps = windowSec > 0 ? (stats.sent - lastSent) / windowSec : 0;
			lastSent = stats.sent; lastAt = now;
			const elapsed = Number(now - startedAt) / 1e9;
			process.stderr.write(`\r${stats.sent} sent | ${stats.failed} failed | ${rps.toFixed(0)} req/s | ${elapsed.toFixed(0)}s elapsed   `);
		}, PROGRESS_INTERVAL_MS);
		progressTimer.unref();
	}

	const record = (item, result) => {
		stats.sent++;
		stats.bytes += result.bytes;
		stats.durations.push(result.ms);
		if (result.ok) stats.ok++; else stats.failed++;
		stats.status.set(result.status, (stats.status.get(result.status) || 0) + 1);
		if (result.error) stats.errors.set(result.error, (stats.errors.get(result.error) || 0) + 1);
		if (result.cache) stats.cache.set(result.cache, (stats.cache.get(result.cache) || 0) + 1);
		if (csvOut) {
			const url = /[",]/.test(item.url) ? '"' + item.url.replace(/"/g, '""') + '"' : item.url;
			csvOut.write(`${url},${result.status},${result.ms.toFixed(1)},${result.bytes},${result.cache || ''}\n`);
		}
	};

	// one worker = one sequential request stream; opts.threads of them run in parallel
	const worker = async () => {
		while (!stopping) {
			const item = await source.next();
			if (!item) return;
			record(item, await doRequest(item, opts, targetUrl, agents));
		}
	};

	await Promise.all(Array.from({ length: opts.threads }, worker));

	const totalMs = Number(process.hrtime.bigint() - startedAt) / 1e6;
	if (progressTimer) clearInterval(progressTimer);
	if (!opts.quiet && process.stderr.isTTY) process.stderr.write('\r' + ' '.repeat(80) + '\r');
	if (csvOut) await new Promise((resolve, reject) => csvOut.end(err => (err ? reject(err) : resolve())));
	agents.http.destroy();
	agents.https.destroy();

	const text = report(stats, totalMs, startedWall, opts);
	console.log(text);
	if (opts.save) console.log(`saved to          ${saveReport(text, startedWall, opts)}`);
	process.exitCode = stats.failed > 0 ? 1 : 0;
}

function report(stats, totalMs, startedWall, opts) {
	const sorted = stats.durations.slice().sort((a, b) => a - b);
	const sum = stats.durations.reduce((a, b) => a + b, 0);
	const totalSec = totalMs / 1000;

	const rule = '-'.repeat(58);
	const out = [];
	const add = text => out.push(text);

	add(rule);
	add('LOAD TEST RESULTS');
	add(rule);
	add(`total runtime     ${formatDuration(totalMs)}`);
	add(`started           ${startedWall.toISOString()}`);
	add(`finished          ${new Date().toISOString()}`);
	add(`threads           ${opts.threads}`);
	add(`target            ${opts.asIs ? '(as recorded)' : opts.target}`);
	add(`requests file     ${opts.requests}`);
	if (opts.limit !== Infinity) add(`limit             ${opts.limit} per pass`);
	if (opts.loop > 1) add(`loop              ${opts.loop} passes`);
	if (opts.skip) add(`skip              ${opts.skip}`);
	add(rule);
	add(`requests          ${stats.sent}`);
	add(`  ok              ${stats.ok}`);
	add(`  failed          ${stats.failed}`);
	add(`throughput        ${totalSec > 0 ? (stats.sent / totalSec).toFixed(1) : '0'} req/s`);
	add(`transferred       ${formatBytes(stats.bytes)} (${totalSec > 0 ? formatBytes(stats.bytes / totalSec) : '0 B'}/s)`);
	add(rule);
	add('latency (ms)');
	add(`  avg             ${stats.sent ? (sum / stats.sent).toFixed(1) : '0'}`);
	add(`  min / max       ${sorted.length ? sorted[0].toFixed(1) : '0'} / ${sorted.length ? sorted[sorted.length - 1].toFixed(1) : '0'}`);
	add(`  p50 / p90       ${percentile(sorted, 50).toFixed(1)} / ${percentile(sorted, 90).toFixed(1)}`);
	add(`  p95 / p99       ${percentile(sorted, 95).toFixed(1)} / ${percentile(sorted, 99).toFixed(1)}`);

	if (stats.status.size) {
		add(rule);
		add('status codes');
		for (const [status, count] of [...stats.status].sort((a, b) => b[1] - a[1]))
			add(`  ${status || 'no response'}`.padEnd(18) + `${count} (${(100 * count / stats.sent).toFixed(1)}%)`);
	}

	if (stats.cache.size) {
		add(rule);
		add('frontcache disposition');
		for (const [kind, count] of [...stats.cache].sort((a, b) => b[1] - a[1]))
			add(`  ${kind}`.padEnd(18) + `${count} (${(100 * count / stats.sent).toFixed(1)}%)`);
	}

	if (stats.errors.size) {
		add(rule);
		add('errors');
		for (const [err, count] of [...stats.errors].sort((a, b) => b[1] - a[1]))
			add(`  ${err}`.padEnd(18) + `${count}`);
	}
	add(rule);
	return out.join('\n');
}

/** Save the report next to the previous runs, named after the moment the run started. */
function saveReport(text, startedWall, opts) {
	const base = fileStamp(startedWall) + (opts.name ? '_' + slug(opts.name) : '');
	fs.mkdirSync(opts.results, { recursive: true });

	// the stamp is second-resolution, so short back-to-back runs would land on the same name
	let file = path.join(opts.results, base + '.txt');
	for (let n = 2; fs.existsSync(file); n++) file = path.join(opts.results, `${base}_${n}.txt`);

	fs.writeFileSync(file, text + '\n');
	return file;
}

main().catch(err => { console.error(err); process.exit(1); });
