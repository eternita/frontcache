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
package org.frontcache.plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import org.frontcache.plugin.serve.EvidenceSigner;

/**
 * What a {@link FrontcachePlugin} may read from the node - handed to {@link FrontcachePlugin#start}, one per
 * plugin. Every answer is read when asked, so a plugin holding the context never holds a stale value.
 *
 * <p>Deliberately small: a plugin never receives the API key, the warm-request token, or a raw property of
 * {@code frontcache.properties}. A plugin bundled into the node's own class path may receive a richer context from
 * the core (an extension of this interface); a plugin written against this API cannot rely on it.
 */
public interface PluginContext {

	/** This plugin's manifest, as the node read and checked it. */
	PluginManifest manifest();

	/**
	 * The site's public hostname ({@code front-cache.default-domain}), or null when the node has none configured -
	 * never the placeholder.
	 */
	String publicDomain();

	/** The node's id ({@code front-cache.id}) - what an alert names the node by. */
	String nodeId();

	/** The core's version, e.g. {@code 2.13.0}. */
	String coreVersion();

	/** FRONTCACHE_HOME, or null for a node started without one. */
	File frontcacheHome();

	/**
	 * This plugin's own directory for state it keeps, {@code FRONTCACHE_HOME/plugins/<id>/data}, created on the
	 * first call; null for a node without FRONTCACHE_HOME or when it cannot be created.
	 */
	File dataDirectory();

	/**
	 * A client key (an event's {@code clientKey}, which is an address and therefore personal data) as a keyed
	 * hash: stable for the life of the process, so an alert can group by it and correlate, and not reversible. The
	 * key is in memory only and differs per node and per run. Null in, null out.
	 */
	String hashClientKey(String clientKey);

	/**
	 * {@link PluginKind#SERVE}: the node's signer of evidence cookies - what the core's {@code cookie-hmac:} predicate
	 * verifies. Null unless the node has a cookie secret ({@code front-cache.visitor.cookie-secret}) and this plugin
	 * declares {@code serve} and names at least one cookie in its manifest.
	 */
	default EvidenceSigner evidenceSigner() {
		return null;
	}

	/**
	 * Where this plugin runs background work - refreshing a list it writes, or the data its predicates answer from (a
	 * {@code decide} plugin answers from memory, never from I/O on the request thread). Threads the node shares between
	 * its plugins - daemon threads, so keep a task short and never block one forever; every task is wrapped, so one that
	 * throws is logged and a periodic one keeps its schedule. Tasks the plugin scheduled are cancelled when it stops - a
	 * {@code reload} included - so schedule them in {@link FrontcachePlugin#start}, and ask for the scheduler in every start: a stopped
	 * start's scheduler takes no task. {@code shutdown()} cancels this
	 * plugin's tasks only. Null only from a context that offers none (a test's own).
	 */
	default ScheduledExecutorService scheduler() {
		return null;
	}

	/**
	 * {@link PluginKind#MANAGE}: replaces {@code conf/lists/<name>.txt} atomically - the sanctioned way for a plugin to
	 * change what the core decides. A guard or bot rule reading the list ({@code client-ip:@lists/<name>.txt}) sees the
	 * new content at once, without a reload; the plugin decides nothing itself.
	 *
	 * <p>Each line is an address or a CIDR block ({@code 203.0.113.7}, {@code 66.249.64.0/19}, IPv6 alike); blank lines
	 * and {@code #} comments are kept as written. <b>Written whole or refused whole</b>: one entry that is not an
	 * address refuses the write, and the list keeps what it had. The file starts with {@code # managed by plugin <id>}:
	 * a list another plugin manages, or one an operator wrote (a file without the marker), is never overwritten.
	 *
	 * @param name the list's name - lower case letters, digits, {@code .}, {@code _} and {@code -}, starting with a
	 *             letter or digit, at most 64 characters; {@code .txt} is added
	 * @param lines the entries, at most 100,000
	 * @return how many address blocks the list now holds
	 * @throws IllegalArgumentException naming the entries that are not addresses, or a name that is not one
	 * @throws IOException when the file cannot be written, belongs to someone else, or the node has no FRONTCACHE_HOME
	 */
	default int writeList(String name, List<String> lines) throws IOException {
		throw new UnsupportedOperationException("this node does not offer writeList");
	}
}
