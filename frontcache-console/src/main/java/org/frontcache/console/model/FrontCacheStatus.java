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
package org.frontcache.console.model;

import java.text.DecimalFormat;

import org.frontcache.cache.CacheStats;
import org.frontcache.core.FcNodeStats;
import org.frontcache.io.NodeStatsActionResponse;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One edge, as the console's "Edges status" block shows it.
 *
 * Also the wire shape of {@code /edges-status.json}, which the realtime page polls - so the numbers
 * here are RAW. Formatting lives in the browser (fc-edges-status.js): a page that renders its first
 * values through {@link DecimalFormat} and every later one through JavaScript has two formatters for
 * one field, and they drift.
 *
 * Every number can be -1, meaning the node did not report it - which is not the same as zero, and is
 * what a node predating {@code get-node-stats} reports for everything except the entry count. See
 * {@link NodeStatsActionResponse}.
 */
public class FrontCacheStatus {

	private String name;
	private String url;
	private long cachedAmount;
	private boolean available;

	private long l1Entries = CacheStats.UNKNOWN;
	private long l1Evictions = CacheStats.UNKNOWN;
	private long l1ContentBytes = CacheStats.UNKNOWN;
	private long l2Entries = CacheStats.UNKNOWN;
	private long l2DiskBytes = CacheStats.UNKNOWN;

	private long heapUsedBytes = FcNodeStats.UNKNOWN;
	private long heapMaxBytes = FcNodeStats.UNKNOWN;
	private double cpuProcessLoad = FcNodeStats.UNKNOWN_LOAD;
	private long uptimeMillis = FcNodeStats.UNKNOWN;

	private static DecimalFormat formater = new DecimalFormat("###,###,###,###");


	public String getName() {
		return name;
	}


	public void setName(String name) {
		this.name = name;
	}


	public long getCachedAmount() {
		return cachedAmount;
	}


	public void setCachedAmount(long cachedAmount) {
		this.cachedAmount = cachedAmount;
	}


	public boolean isAvailable() {
		return available;
	}


	public void setAvailable(boolean available) {
		this.available = available;
	}

	public String getOnlineStatus()
	{
		return isAvailable() ? "ONLINE" : "OFFLINE";
	}

	/**
	 * Not on the wire: the polled page formats its own numbers, and shipping a pre-formatted one
	 * invites a second formatter that only some of the values go through.
	 */
	@JsonIgnore
	public String getCachedAmountString() {
		return formater.format(cachedAmount);
	}

	/** Copies what a node reported. A null response leaves every number at its UNKNOWN default. */
	public void readFrom(NodeStatsActionResponse stats) {
		if (null == stats)
			return;

		this.cachedAmount = stats.getCachedEntries();
		this.l1Entries = stats.getL1Entries();
		this.l1Evictions = stats.getL1Evictions();
		this.l1ContentBytes = stats.getL1ContentBytes();
		this.l2Entries = stats.getL2Entries();
		this.l2DiskBytes = stats.getL2DiskBytes();
		this.heapUsedBytes = stats.getHeapUsedBytes();
		this.heapMaxBytes = stats.getHeapMaxBytes();
		this.cpuProcessLoad = stats.getCpuProcessLoad();
		this.uptimeMillis = stats.getUptimeMillis();
	}

	public long getL1Entries() {
		return l1Entries;
	}

	public void setL1Entries(long l1Entries) {
		this.l1Entries = l1Entries;
	}

	public long getL1Evictions() {
		return l1Evictions;
	}

	public void setL1Evictions(long l1Evictions) {
		this.l1Evictions = l1Evictions;
	}

	public long getL1ContentBytes() {
		return l1ContentBytes;
	}

	public void setL1ContentBytes(long l1ContentBytes) {
		this.l1ContentBytes = l1ContentBytes;
	}

	public long getL2Entries() {
		return l2Entries;
	}

	public void setL2Entries(long l2Entries) {
		this.l2Entries = l2Entries;
	}

	public long getL2DiskBytes() {
		return l2DiskBytes;
	}

	public void setL2DiskBytes(long l2DiskBytes) {
		this.l2DiskBytes = l2DiskBytes;
	}

	public long getHeapUsedBytes() {
		return heapUsedBytes;
	}

	public void setHeapUsedBytes(long heapUsedBytes) {
		this.heapUsedBytes = heapUsedBytes;
	}

	public long getHeapMaxBytes() {
		return heapMaxBytes;
	}

	public void setHeapMaxBytes(long heapMaxBytes) {
		this.heapMaxBytes = heapMaxBytes;
	}

	public double getCpuProcessLoad() {
		return cpuProcessLoad;
	}

	public void setCpuProcessLoad(double cpuProcessLoad) {
		this.cpuProcessLoad = cpuProcessLoad;
	}

	public long getUptimeMillis() {
		return uptimeMillis;
	}

	public void setUptimeMillis(long uptimeMillis) {
		this.uptimeMillis = uptimeMillis;
	}


	public String getUrl() {
		return url;
	}


	public void setUrl(String url) {
		this.url = url;
	}

}
