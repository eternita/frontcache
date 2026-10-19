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
 * One edge's copy of one config file, for a Configs screen.
 *
 * <h2>The url is here because the name is not unique</h2>
 *
 * This replaces the five one-field model classes ({@code BotConfig}, {@code GuardConfig},
 * {@code ResilienceConfig}, {@code DynamicURLsConfig}, {@code FallbackConfig}) and, more
 * importantly, replaces a {@code Map<String, String>} keyed by {@code FrontCacheClient.getName()}.
 *
 * That name is derived from the URL by stripping the scheme and everything from the first colon, so
 * two edges on the same host at different ports - <b>exactly the e2e topology, localhost:8080 and
 * localhost:9080</b> - produced the same key, and one of the two tabs silently disappeared from the
 * page. Read-only that cost a tab. With a Save button on the tab it would send an edit to whichever
 * node won the {@code put}, which is not necessarily the node whose file is on screen.
 *
 * So the url is the identity, and every write is addressed by it - the way {@code invalidateEdge},
 * {@code purgeEdge} and {@code dumpKeys} already are.
 *
 * <h2>A class with getters, and NOT a record</h2>
 *
 * This is immutable, four fields wide and would be a one-line record - and it cannot be one. The
 * Configs screens read it from JSP EL, which resolves {@code ${edge.url}} through <b>JavaBeans</b>
 * accessors; a record's accessor is {@code url()}, so EL fails with {@code PropertyNotFoundException:
 * The class ... does not have the property 'url'} at render time. Nothing at compile time says so.
 * A record with {@code getUrl()} bolted on would work and read worse than this.
 */
public class EdgeConfigFile {

	/** Display name, which may repeat across edges. */
	private final String name;

	/** The edge's base URL - the identity, and what a save is addressed to. */
	private final String url;

	/** The file as it is on the edge's disk, or null when the edge has no such file. */
	private final String content;

	/**
	 * False when the edge did not answer at all.
	 *
	 * Different from answering "I have no such file", and the screen says the two differently - a
	 * node that is down and a node with no conf/guard-rules.conf are not the same situation.
	 */
	private final boolean available;

	public EdgeConfigFile(String name, String url, String content, boolean available) {
		this.name = name;
		this.url = url;
		this.content = content;
		this.available = available;
	}

	/** A tab for an edge that did not answer, so it is visible as unreachable rather than absent. */
	public static EdgeConfigFile unreachable(String name, String url) {
		return new EdgeConfigFile(name, url, null, false);
	}

	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
	}

	public String getContent() {
		return content;
	}

	public boolean isAvailable() {
		return available;
	}

}
