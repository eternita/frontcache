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
 * {@code crawler-feeder-verify}: the address as checked, and one check per feed with domains - written as JSON by the
 * node from the getters.
 */
public class FeederVerifyResponse extends ActionResponse {

	/** One feed's verdict on the address. */
	public static class Check {
		private String feed;
		private String outcome;
		private String host;
		private String reason;
		private boolean listed;

		public String getFeed() {
			return feed;
		}

		public void setFeed(String feed) {
			this.feed = feed;
		}

		/** {@code confirmed}, {@code refuted} (a name outside the domains, or not resolving back) or {@code unknown}. */
		public String getOutcome() {
			return outcome;
		}

		public void setOutcome(String outcome) {
			this.outcome = outcome;
		}

		/** The address's reverse DNS name, when it has one. */
		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		/** Why it is not confirmed; null when it is. */
		public String getReason() {
			return reason;
		}

		public void setReason(String reason) {
			this.reason = reason;
		}

		/** Whether the address is on the feed's list as a confirmed claimant now. */
		public boolean isListed() {
			return listed;
		}

		public void setListed(boolean listed) {
			this.listed = listed;
		}
	}

	private String address;
	private List<Check> checks = new ArrayList<>();

	public FeederVerifyResponse() {
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public List<Check> getChecks() {
		return checks;
	}

	public void setChecks(List<Check> checks) {
		this.checks = checks;
	}

	/** The check of that feed, or null. */
	public Check check(String feed) {
		for (Check c : checks)
			if (c.getFeed().equals(feed))
				return c;
		return null;
	}
}
