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
package org.frontcache.examples.crawlerfeeder;

import java.util.ArrayList;
import java.util.List;

import org.frontcache.io.ActionResponse;

/**
 * {@code crawler-feeder-status} (and {@code crawler-feeder-refresh}): one entry per feed, and the claimant checks'
 * counters. Written as JSON by the node from the getters. Never holds a claimant that was not confirmed - an address
 * is personal data; a confirmed one is a crawler's, and is on the list anyway.
 */
public class FeederStatusResponse extends ActionResponse {

	/** A claimant confirmed by reverse DNS. Times are ISO-8601 instants. */
	public record Confirmed(String address, String host, String confirmed, String until) {
	}

	/** What the reverse-DNS checks did since the plugin was loaded, and how they are configured. */
	public static class Claimants {
		private String keep;
		private String retry;
		private int maxVerified;
		private long claims;
		private long lookups;
		private long confirmed;
		private long refuted;
		private long unknown;
		private long dropped;
		private long ignored;
		private int pending;

		/** How long a confirmation lasts. */
		public String getKeep() {
			return keep;
		}

		public void setKeep(String keep) {
			this.keep = keep;
		}

		/** How long an address not confirmed is not looked up again. */
		public String getRetry() {
			return retry;
		}

		public void setRetry(String retry) {
			this.retry = retry;
		}

		public int getMaxVerified() {
			return maxVerified;
		}

		public void setMaxVerified(int maxVerified) {
			this.maxVerified = maxVerified;
		}

		/** Matches of a claim rule delivered to the plugin. */
		public long getClaims() {
			return claims;
		}

		public void setClaims(long claims) {
			this.claims = claims;
		}

		/** Checks made - each one or two lookups. */
		public long getLookups() {
			return lookups;
		}

		public void setLookups(long lookups) {
			this.lookups = lookups;
		}

		public long getConfirmed() {
			return confirmed;
		}

		public void setConfirmed(long confirmed) {
			this.confirmed = confirmed;
		}

		/** A name outside the domains, or one that does not resolve back to the address. */
		public long getRefuted() {
			return refuted;
		}

		public void setRefuted(long refuted) {
			this.refuted = refuted;
		}

		/** No name, or a lookup that failed. */
		public long getUnknown() {
			return unknown;
		}

		public void setUnknown(long unknown) {
			this.unknown = unknown;
		}

		/** Claimants not checked because the queue was full. */
		public long getDropped() {
			return dropped;
		}

		public void setDropped(long dropped) {
			this.dropped = dropped;
		}

		/** Claims whose address is not a public one (loopback, private, ...) - never looked up. */
		public long getIgnored() {
			return ignored;
		}

		public void setIgnored(long ignored) {
			this.ignored = ignored;
		}

		public int getPending() {
			return pending;
		}

		public void setPending(int pending) {
			this.pending = pending;
		}
	}

	/** One feed's state. Times are ISO-8601 instants, null when it never happened. */
	public static class FeedStatus {
		private String name;
		private String url;
		private String every;
		private String list;
		private String lastAttempt;
		private String lastSuccess;
		private int blocks = -1;
		private String lastError;
		private int consecutiveFailures;
		private long nextRunSeconds = -1;
		private List<String> domains = List.of();
		private List<String> rules = List.of();
		private int verified;
		private String writeError;
		private List<Confirmed> confirmed = new ArrayList<>();

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getEvery() {
			return every;
		}

		public void setEvery(String every) {
			this.every = every;
		}

		/** The list it keeps: {@code lists/<name>.txt}. */
		public String getList() {
			return list;
		}

		public void setList(String list) {
			this.list = list;
		}

		public String getLastAttempt() {
			return lastAttempt;
		}

		public void setLastAttempt(String lastAttempt) {
			this.lastAttempt = lastAttempt;
		}

		public String getLastSuccess() {
			return lastSuccess;
		}

		public void setLastSuccess(String lastSuccess) {
			this.lastSuccess = lastSuccess;
		}

		/** The address blocks the list held after the last success; -1 before one. */
		public int getBlocks() {
			return blocks;
		}

		public void setBlocks(int blocks) {
			this.blocks = blocks;
		}

		/** Why the last attempt kept the list as it was; null after a success. */
		public String getLastError() {
			return lastError;
		}

		public void setLastError(String lastError) {
			this.lastError = lastError;
		}

		public int getConsecutiveFailures() {
			return consecutiveFailures;
		}

		public void setConsecutiveFailures(int consecutiveFailures) {
			this.consecutiveFailures = consecutiveFailures;
		}

		/** Seconds until the next scheduled fetch; -1 when none is scheduled. */
		public long getNextRunSeconds() {
			return nextRunSeconds;
		}

		public void setNextRunSeconds(long nextRunSeconds) {
			this.nextRunSeconds = nextRunSeconds;
		}

		/** The domains a claimant's reverse DNS name must be under; empty when the feed checks none. */
		public List<String> getDomains() {
			return domains;
		}

		public void setDomains(List<String> domains) {
			this.domains = domains;
		}

		/** The guard rules whose matches report a claimant to this feed. */
		public List<String> getRules() {
			return rules;
		}

		public void setRules(List<String> rules) {
			this.rules = rules;
		}

		/** Claimants confirmed by reverse DNS on the list now. */
		public int getVerified() {
			return verified;
		}

		public void setVerified(int verified) {
			this.verified = verified;
		}

		/** Why the list was not rewritten after a confirmation or an expiry; null after a write. */
		public String getWriteError() {
			return writeError;
		}

		public void setWriteError(String writeError) {
			this.writeError = writeError;
		}

		/** The newest confirmations, at most 20. */
		public List<Confirmed> getConfirmed() {
			return confirmed;
		}

		public void setConfirmed(List<Confirmed> confirmed) {
			this.confirmed = confirmed;
		}
	}

	private List<FeedStatus> feeds = new ArrayList<>();
	private Claimants claimants = new Claimants();

	public FeederStatusResponse() {
	}

	public List<FeedStatus> getFeeds() {
		return feeds;
	}

	public void setFeeds(List<FeedStatus> feeds) {
		this.feeds = feeds;
	}

	public Claimants getClaimants() {
		return claimants;
	}

	public void setClaimants(Claimants claimants) {
		this.claimants = claimants;
	}

	/** The feed of that name, or null. */
	public FeedStatus feed(String name) {
		for (FeedStatus f : feeds)
			if (f.getName().equals(name))
				return f;
		return null;
	}
}
