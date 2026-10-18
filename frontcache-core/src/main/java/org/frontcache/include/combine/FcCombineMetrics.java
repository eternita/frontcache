/*
 * Copyright 2015 Eternita
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
package org.frontcache.include.combine;

import java.util.concurrent.atomic.LongAdder;

/**
 * Combine-and-reduce outcomes, counted per batch and per member.
 * See docs/archive/combine-reduce-proposal.md section 12.
 *
 * <h2>What these are for</h2>
 *
 * The feature's whole claim is "N fragments, one origin request", and nothing else in the process can
 * tell you whether that is actually happening. The command metrics count origin calls but cannot say
 * how many fragments each one carried; the cache metrics count hits but a combined batch IS the miss
 * path. So the ratio that matters -
 *
 * <pre>
 * rate(frontcache_include_combine_members_total{result="combined"}[5m])
 *   / rate(frontcache_include_combine_batches_total{result="ok"}[5m])
 * </pre>
 *
 * average members per batch - is computed from two counters taken here.
 *
 * <h2>Counters, not a distribution summary</h2>
 *
 * Batch size is the obvious candidate for a {@code DistributionSummary}, and it is deliberately not
 * one. A summary has to be recorded into a Micrometer meter at event time, which would put a hard
 * dependency on the (optional, off-by-default) metrics registry on the include path. Two monotonic
 * counters give the mean, are free when export is off, and follow {@link
 * org.frontcache.cache.FcCacheMetrics}: count where the answer is known, let Prometheus compute the
 * windows.
 *
 * <h2>The accounting is exhaustive</h2>
 *
 * Every include that reaches {@code isCombinable} as a candidate - it carries {@code combine="..."}
 * and combining is enabled - lands in exactly one {@link Member} bucket, so
 * {@code cached + combined + individual + fallback} is the number of combine candidates seen. That is
 * an invariant, and {@code CombineMetricsTest} holds it: a member silently accounted twice, or not at
 * all, would make the members-per-batch ratio quietly wrong in a way no single counter reveals.
 */
public class FcCombineMetrics {

	/** How a combined origin call ended. */
	public enum Batch {

		/** A well-formed envelope came back and its parts were applied. */
		OK("ok"),

		/** A 2xx with no {@code x-frontcache-combine} header: this origin has no combine handler. */
		UNSUPPORTED("unsupported"),

		/** The origin claims support and sent something unusable - see {@link Error}. An origin bug. */
		ERROR("error"),

		/** The call itself did not produce an origin answer: timeout, circuit open, 5xx, bulkhead. */
		FAILED("failed");

		private final String result;

		Batch(String result) {
			this.result = result;
		}

		public String getResult() {
			return result;
		}
	}

	/** How one combine candidate was ultimately resolved. */
	public enum Member {

		/** The cache probe served it, so it never joined a batch. The steady state on a warm page. */
		CACHED("cached"),

		/** Served from a part of a combined response. The one this feature exists to raise. */
		COMBINED("combined"),

		/**
		 * Resolved as an ordinary single include after all: the only member of its group, its group
		 * memoised as unsupported, its own query inside the reserved {@code fc-} space, or the batch
		 * came back from an origin with no combine handler.
		 */
		INDIVIDUAL("individual"),

		/** Got no content and fell back: the batch failed, or its part carried a non-2xx status. */
		FALLBACK("fallback");

		private final String result;

		Member(String result) {
			this.result = result;
		}

		public String getResult() {
			return result;
		}
	}

	/**
	 * Why an envelope was rejected. This is the series to alert on: every value here is an origin that
	 * says it implements the contract and does not, which no other signal reports - the page still
	 * renders, out of fallbacks.
	 */
	public enum Error {

		/** The body would not parse as an envelope. */
		PARSE("parse"),

		/** The part count did not match the member count. */
		COUNT("count"),

		/** A {@code q} echo named a member other than the one sent at that position. */
		ECHO("echo");

		private final String kind;

		Error(String kind) {
			this.kind = kind;
		}

		public String getKind() {
			return kind;
		}
	}

	private static final LongAdder[] batches = newCounts(Batch.values().length);
	private static final LongAdder[] members = newCounts(Member.values().length);
	private static final LongAdder[] errors = newCounts(Error.values().length);

	private FcCombineMetrics() { }

	private static LongAdder[] newCounts(int size)
	{
		LongAdder[] adders = new LongAdder[size];
		for (int i = 0; i < size; i++)
			adders[i] = new LongAdder();

		return adders;
	}

	/**
	 * Records one combined origin call. Like {@code FcCacheMetrics.record}, this runs on the request
	 * path and is a {@link LongAdder} increment and nothing else - no allocation, no lock, and no
	 * check for whether export is even enabled. Counting unconditionally keeps the numbers right for a
	 * node that has export switched on later, and costs less than the check would.
	 */
	public static void record(Batch outcome)
	{
		batches[outcome.ordinal()].increment();
	}

	/** Records one combine candidate's resolution. */
	public static void record(Member outcome)
	{
		members[outcome.ordinal()].increment();
	}

	/** Records {@code count} candidates resolved the same way - the batch paths resolve in bulk. */
	public static void record(Member outcome, int count)
	{
		members[outcome.ordinal()].add(count);
	}

	/** Records one rejected envelope. Always paired with a {@link Batch#ERROR}. */
	public static void record(Error kind)
	{
		errors[kind.ordinal()].increment();
	}

	public static long count(Batch outcome)
	{
		return batches[outcome.ordinal()].sum();
	}

	public static long count(Member outcome)
	{
		return members[outcome.ordinal()].sum();
	}

	public static long count(Error kind)
	{
		return errors[kind.ordinal()].sum();
	}

	/** Test seam - back to zero. */
	public static void reset()
	{
		for (LongAdder adder : batches)
			adder.reset();
		for (LongAdder adder : members)
			adder.reset();
		for (LongAdder adder : errors)
			adder.reset();
	}
}
