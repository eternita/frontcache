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
 * {@code crawler-feeder-status} (and {@code crawler-feeder-refresh}): one entry per feed. Written as JSON by the node
 * from the getters.
 */
public class FeederStatusResponse extends ActionResponse {

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
	}

	private List<FeedStatus> feeds = new ArrayList<>();

	public FeederStatusResponse() {
	}

	public List<FeedStatus> getFeeds() {
		return feeds;
	}

	public void setFeeds(List<FeedStatus> feeds) {
		this.feeds = feeds;
	}

	/** The feed of that name, or null. */
	public FeedStatus feed(String name) {
		for (FeedStatus f : feeds)
			if (f.getName().equals(name))
				return f;
		return null;
	}
}
