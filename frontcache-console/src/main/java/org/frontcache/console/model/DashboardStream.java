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

/**
 * One edge the realtime page subscribes to, serialized to JSON for the page.
 *
 * Only the edge URL travels, not the stream URL: the path the metrics stream lives on is
 * {@code DashboardStreamProxyServlet}'s business, and the browser is not allowed to pick it.
 *
 * Was {@code HystrixConnection}, and carried Netflix-era {@code auth} / {@code delay} fields that
 * Frontcache never set - they existed for pointing the vendored dashboard at an arbitrary Turbine
 * stream, which the console does not do.
 *
 * <h2>{@code cluster} is why this is the one JSON here with no compatibility burden</h2>
 *
 * The node's stream format and the management API both have to work in a mixed fleet, in either
 * upgrade order, which is why every change to them carries both shapes. This one does not: the
 * console serves it to its own page, and both halves ship in the same war. So {@code cluster} is
 * simply a new key - null for an edge in no cluster, which is every edge on a config file written
 * before {@code frontcache.console.clusters} existed.
 */
public class DashboardStream {

	private String name;
	private String url;

	/** The cluster this edge belongs to, or null when it belongs to none. */
	private String cluster;

	public DashboardStream() {
	}

	public DashboardStream(String name, String url) {
		this(name, url, null);
	}

	public DashboardStream(String name, String url, String cluster) {
		this.name = name;
		this.url = url;
		this.cluster = cluster;
	}

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

	public String getCluster() {
		return cluster;
	}

	public void setCluster(String cluster) {
		this.cluster = cluster;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((url == null) ? 0 : url.hashCode());
		result = prime * result + ((cluster == null) ? 0 : cluster.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;

		DashboardStream other = (DashboardStream) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;

		if (url == null) {
			if (other.url != null)
				return false;
		} else if (!url.equals(other.url))
			return false;

		if (cluster == null) {
			if (other.cluster != null)
				return false;
		} else if (!cluster.equals(other.cluster))
			return false;

		return true;
	}

	@Override
	public String toString() {
		return "DashboardStream{name=" + name + ", url=" + url + ", cluster=" + cluster + "}";
	}

}
