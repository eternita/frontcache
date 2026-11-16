/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.plugin.host;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@code decide} plugin's budget: every call into its predicates is timed, and a plugin whose p99 over a minute
 * is over its budget ({@value #BUDGET_PROPERTY}, default {@value #DEFAULT_BUDGET_MICROS} microseconds), or that
 * threw on more than 1 % of the minute's calls, is <b>switched off</b> for {@value #OFF_MINUTES} minutes: its
 * predicates have no answer, so the rules naming them do not match. Then it is tried again.
 *
 * <p>The minute is judged once it is over, by whichever call crosses into the next one - no thread of its own - and
 * only when it holds {@value #MIN_CALLS} calls or more: a plugin's first calls (class loading, a cold JIT) must not
 * switch it off on a quiet site. A predicate cannot be interrupted mid-call; the budget bounds what it costs over time.
 *
 * <p>The process's counters ({@link #calls()}, ...) are never reset - a restart of the plugin keeps them.
 */
final class DecideBudget {

	private static final Logger logger = LoggerFactory.getLogger(DecideBudget.class);

	/** {@code front-cache.plugins.<id>.decide-budget}: {@code 200us}, {@code 0.5ms}, or a number of microseconds. */
	static final String BUDGET_PROPERTY = "front-cache.plugins.%s.decide-budget";

	static final long DEFAULT_BUDGET_MICROS = 200;

	static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

	static final int OFF_MINUTES = 5;

	static final long MIN_CALLS = 100;

	/** A call is recorded as at most this long - a predicate that took longer is over any budget anyway. */
	private static final long HIGHEST_TRACKABLE_NANOS = TimeUnit.SECONDS.toNanos(60);

	/** The clock, in nanoseconds - a test's to set. */
	static volatile LongSupplier nanoClock = System::nanoTime;

	private final String plugin;
	private final long budgetNanos;

	private final Recorder recorder = new Recorder(HIGHEST_TRACKABLE_NANOS, 2);
	private final AtomicLong windowStart;
	private final LongAdder windowErrors = new LongAdder();
	private final AtomicBoolean errorLogged = new AtomicBoolean();

	private final LongAdder calls = new LongAdder();
	private final LongAdder errors = new LongAdder();
	private final LongAdder switchedOff = new LongAdder();

	/** While switched off: the clock's value it is tried again at; 0 while it answers. */
	private final AtomicLong offUntil = new AtomicLong();
	private volatile long retryAtMillis;
	private volatile String reason;
	private volatile double lastP99Micros = -1;

	DecideBudget(String plugin) {
		this(plugin, budgetNanos(plugin));
	}

	DecideBudget(String plugin, long budgetNanos) {
		this.plugin = plugin;
		this.budgetNanos = budgetNanos;
		this.windowStart = new AtomicLong(nanoClock.getAsLong());
	}

	/** Reads {@value #BUDGET_PROPERTY}; a value that is not a duration is the default, with a WARN. */
	static long budgetNanos(String plugin) {
		String property = String.format(BUDGET_PROPERTY, plugin);
		String value = FCConfig.getProperty(property);
		if (null == value || value.isBlank())
			return TimeUnit.MICROSECONDS.toNanos(DEFAULT_BUDGET_MICROS);
		long nanos = parseDuration(value);
		if (0 < nanos)
			return nanos;
		logger.warn("{} is not a duration: '{}' - using {}us (write e.g. 200us, 0.5ms, or a number of microseconds)",
				property, value, DEFAULT_BUDGET_MICROS);
		return TimeUnit.MICROSECONDS.toNanos(DEFAULT_BUDGET_MICROS);
	}

	/** @return nanoseconds, or -1 when it is not a positive duration */
	static long parseDuration(String value) {
		String v = value.trim().toLowerCase(java.util.Locale.ROOT).replace('µ', 'u');
		double scale = 1_000; // microseconds by default
		String number = v;
		if (v.endsWith("ns")) {
			scale = 1;
			number = v.substring(0, v.length() - 2);
		} else if (v.endsWith("us")) {
			number = v.substring(0, v.length() - 2);
		} else if (v.endsWith("ms")) {
			scale = 1_000_000;
			number = v.substring(0, v.length() - 2);
		}
		try {
			double n = Double.parseDouble(number.trim());
			long nanos = Math.round(n * scale);
			return (n > 0 && nanos > 0) ? nanos : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Whether the plugin's predicates may be asked now. Switched off: no, until its time is up - then it is tried again,
	 * with a fresh minute.
	 */
	boolean admits() {
		long until = offUntil.get();
		if (0 == until)
			return true;
		long now = nanoClock.getAsLong();
		if (now - until < 0)
			return false;
		if (offUntil.compareAndSet(until, 0)) {
			recorder.reset();
			windowErrors.reset();
			windowStart.set(now);
			retryAtMillis = 0;
			logger.warn("Plugin '{}': its predicates are tried again after {} minutes switched off ({})", plugin,
					OFF_MINUTES, reason);
			CoreEvents.pluginStateChanged(plugin, "started", "its predicates are tried again after being switched off");
		}
		return true;
	}

	/** A call that answered, and how long it took. */
	void record(long nanos) {
		calls.increment();
		recorder.recordValue(Math.max(0, Math.min(nanos, HIGHEST_TRACKABLE_NANOS)));
		maybeJudge();
	}

	/** A call that threw. */
	void failed(long nanos, Throwable t, String predicate) {
		calls.increment();
		errors.increment();
		windowErrors.increment();
		recorder.recordValue(Math.max(0, Math.min(nanos, HIGHEST_TRACKABLE_NANOS)));
		// once a minute: the request thread is not the place to log a stack trace per call
		if (errorLogged.compareAndSet(false, true))
			logger.error("Plugin '" + plugin + "': predicate '" + predicate + "' threw - the rules naming it do not match"
					+ " that request (logged once a minute)", t);
		maybeJudge();
	}

	private void maybeJudge() {
		long start = windowStart.get();
		long now = nanoClock.getAsLong();
		if (now - start < WINDOW_NANOS || !windowStart.compareAndSet(start, now))
			return;
		judge(now);
	}

	private void judge(long now) {
		Histogram minute = recorder.getIntervalHistogram();
		long n = minute.getTotalCount();
		long failed = windowErrors.sumThenReset();
		errorLogged.set(false);
		if (n < MIN_CALLS)
			return;

		long p99 = minute.getValueAtPercentile(99.0);
		lastP99Micros = p99 / 1_000.0;
		if (p99 > budgetNanos)
			switchOff(now, String.format(java.util.Locale.ROOT, "p99 %.1fus over its budget of %.1fus, over %d calls in a minute",
					p99 / 1_000.0, budgetNanos / 1_000.0, n));
		else if (failed * 100 > n)
			switchOff(now, "threw on " + failed + " of " + n + " calls in a minute (over 1%)");
	}

	private void switchOff(long now, String why) {
		long until = now + TimeUnit.MINUTES.toNanos(OFF_MINUTES);
		if (0 == until)
			until = 1;
		if (!offUntil.compareAndSet(0, until))
			return;
		reason = why;
		retryAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(OFF_MINUTES);
		switchedOff.increment();
		logger.error("Plugin '{}' is switched off for {} minutes - its predicates have no answer and the rules naming"
				+ " them do not match: {}", plugin, OFF_MINUTES, why);
		CoreEvents.pluginStateChanged(plugin, "over-budget", why);
	}

	boolean isOverBudget() {
		return 0 != offUntil.get();
	}

	long budgetNanos() {
		return budgetNanos;
	}

	long calls() {
		return calls.sum();
	}

	long errors() {
		return errors.sum();
	}

	long switchedOff() {
		return switchedOff.sum();
	}

	/** Microseconds; -1 before a minute was judged. */
	double lastP99Micros() {
		return lastP99Micros;
	}

	/** Epoch millis it is tried again at while switched off; 0 otherwise. */
	long retryAtMillis() {
		return isOverBudget() ? retryAtMillis : 0;
	}

	/** Why it was last switched off; null when it never was. */
	String reason() {
		return reason;
	}
}
