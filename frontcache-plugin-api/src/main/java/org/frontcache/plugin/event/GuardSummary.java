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
package org.frontcache.plugin.event;

import java.util.List;

/**
 * What the guard did over the last interval (every 10 seconds while anyone subscribes) - what threshold alerts are
 * built on: "more than 500 in 5 minutes" is a sum of summaries, not 500 events.
 *
 * @param intervalMillis the interval these counts cover
 * @param rules          every loaded rule, with how often it matched in the interval (zero included)
 * @param buckets        every rate-limit bucket, with how many clients went over its limit in the interval
 */
public record GuardSummary(long time, String node, String site, long intervalMillis, List<RuleCount> rules,
		List<BucketCount> buckets) implements FcEvent {

	public static final String TYPE = "guard.summary";

	public GuardSummary {
		rules = List.copyOf(rules);
		buckets = List.copyOf(buckets);
	}

	@Override
	public String type() {
		return TYPE;
	}

	/**
	 * @param rule        the rule's name
	 * @param disposition what a match of it does: {@code rejected}, {@code redirected}, {@code challenged},
	 *                    {@code allowed} or {@code dry-run}
	 * @param matched     matches in the interval
	 */
	public record RuleCount(String rule, String disposition, long matched) {
	}

	/**
	 * @param bucket     the bucket's name
	 * @param limit      requests allowed per window
	 * @param overLimit  clients that went over the limit in the interval - each counted once per window
	 */
	public record BucketCount(String bucket, int limit, long windowMillis, long overLimit) {
	}
}
