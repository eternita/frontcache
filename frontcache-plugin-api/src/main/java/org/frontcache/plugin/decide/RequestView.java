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
package org.frontcache.plugin.decide;

import java.util.Set;

/**
 * A read-only view of the request a guard or bot rule is deciding - no servlet request, no response, no cache. Every
 * answer but {@link #cookieNames()} may be null: an include's request may already be recycled, and the node's load-time probes have no request
 * at all.
 *
 * <p>Like a serving plugin's view, it does not show {@code Authorization}, {@code Proxy-Authorization}, {@code Cookie}
 * (a predicate reads {@link #cookieNames() names}, never values) or any {@code x-frontcache-*} header.
 */
public interface RequestView {

	/** {@code GET}, {@code POST}, ... */
	String method();

	/** The path, without the query string. */
	String uri();

	/** The query string without its {@code ?}, or null. */
	String query();

	/** The host the request addressed, as the node resolved it. */
	String host();

	/** A header's first value, or null - never one of the headers this view hides. Case-insensitive. */
	String header(String name);

	/** The names of the cookies the request carries - never their values. Never null: empty when none or unknown. */
	Set<String> cookieNames();

	/**
	 * The visitor's address as the node resolved it - through {@code front-cache.trusted-proxies} only, never a header
	 * a client could set - or null. Personal data.
	 */
	String clientAddress();

	/** The {@code bots.conf} verdict - {@code bot}, {@code guest}, ... - or null while bots.conf itself is deciding. */
	String clientType();

	/** {@code toplevel}, {@code include} or {@code include-async}. */
	String requestType();
}
