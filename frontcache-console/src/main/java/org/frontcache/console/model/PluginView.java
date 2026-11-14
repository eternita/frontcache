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
package org.frontcache.console.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.frontcache.io.PluginStats;

/**
 * One plugin of one edge, for the Plugins screen: the node's {@link PluginStats} as it answered them
 * ({@link #getStats()}), plus what JSP EL cannot work out by itself - whether it decides, whether its config can be
 * reloaded, and the epoch-millisecond times as text.
 */
public class PluginView {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
			.withZone(ZoneOffset.UTC);

	private final PluginStats stats;

	public PluginView(PluginStats stats) {
		this.stats = stats;
	}

	public PluginStats getStats() {
		return stats;
	}

	/** {@code decide}: it offers predicates, or declares the kind - the screen shows its budget and its state. */
	public boolean isDecides() {
		return !stats.getPredicates().isEmpty() || stats.getKinds().contains("decide")
				|| null != stats.getDecideState();
	}

	/** Switched off for going over its budget or throwing - its rules do not match. */
	public boolean isOverBudget() {
		return PluginStats.DECIDE_OVER_BUDGET.equals(stats.getDecideState());
	}

	/** {@code serve}: it declares the kind or answers {@code /fc-x/<id>/}. */
	public boolean isServing() {
		return stats.isServes() || stats.getKinds().contains("serve");
	}

	/**
	 * Whether a reload of its config means anything: it has one, and the node loaded the plugin - a refused plugin
	 * and a directory {@code front-cache.plugins} does not list have nothing to reload.
	 */
	public boolean isReloadable() {
		return null != stats.getConfig() && !"refused".equals(stats.getState())
				&& !PluginStats.STATE_NOT_ENABLED.equals(stats.getState());
	}

	/** When the config it runs on was accepted, as text; null when never. */
	public String getConfigLoadedText() {
		return time(stats.getConfigLoaded());
	}

	/** While over budget, when it is tried again, as text; null when not. */
	public String getDecideRetryAtText() {
		return time(stats.getDecideRetryAt());
	}

	/** The predicates' p99 in microseconds, as text; null before the first judged minute (-1 on the wire). */
	public String getDecideP99Text() {
		double p99 = stats.getDecideP99Micros();
		if (p99 < 0)
			return null;
		return (p99 == Math.rint(p99)) ? Long.toString((long) p99) : String.format(java.util.Locale.ROOT, "%.1f", p99);
	}

	private static String time(long epochMillis) {
		return (epochMillis <= 0) ? null : TIME.format(Instant.ofEpochMilli(epochMillis));
	}
}
