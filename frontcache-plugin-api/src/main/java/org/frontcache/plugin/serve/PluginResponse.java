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
package org.frontcache.plugin.serve;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Set;

/**
 * What a {@link PluginEndpoint} answers. Buffered: the node writes it once {@code handle} returns, adding
 * {@code Cache-Control: no-store} and {@code X-Content-Type-Options: nosniff}.
 *
 * <p>Deliberately narrower than a servlet response: a header outside {@link #HEADERS}, a cookie the manifest does not
 * name, or a redirect to a host the node does not publish is refused with an {@link IllegalArgumentException}.
 */
public interface PluginResponse {

	/**
	 * The headers a plugin may set (case-insensitive). Not {@code Set-Cookie} ({@link #cookie}), {@code Location}
	 * ({@link #redirect}) or {@code Cache-Control} (the node's: {@code no-store}).
	 */
	Set<String> HEADERS = Set.of("content-type", "content-language", "retry-after", "vary", "etag", "last-modified",
			"content-security-policy", "referrer-policy", "x-frame-options", "permissions-policy",
			"cross-origin-opener-policy", "cross-origin-resource-policy", "link");

	/** The status, 200 until set. */
	void status(int status);

	/**
	 * Sets a header, replacing an earlier value.
	 *
	 * @throws IllegalArgumentException for a name outside {@link #HEADERS}, or a value with a line break
	 */
	void header(String name, String value);

	/** Shorthand for the {@code Content-Type} header. */
	default void contentType(String contentType) {
		header("Content-Type", contentType);
	}

	/**
	 * A redirect, checked the way a guard rule's redirect target is: a path on this site ({@code /...}, never
	 * {@code //...}), or an absolute {@code http(s)} URL to {@code front-cache.default-domain} or a host in
	 * {@code front-cache.guard-rules.allowed-redirect-hosts}. Anything else - a return address a visitor could have
	 * chosen - is replaced by {@code /}, so a plugin cannot be made an open redirect.
	 *
	 * @param status 301, 302, 303, 307 or 308
	 * @return the target the response will carry
	 */
	String redirect(int status, String target);

	/**
	 * Sets a cookie the manifest names in {@code cookies}: {@code Path=/}, {@code HttpOnly}, {@code SameSite=Lax},
	 * and {@code Secure} when the visitor's scheme is {@code https}.
	 *
	 * @param maxAge how long the browser keeps it; zero deletes it
	 * @throws IllegalArgumentException for a name the manifest does not name, or a value that is not a cookie token
	 */
	void cookie(String name, String value, Duration maxAge);

	/** The body, as bytes. Bounded: a body over 2 MiB fails the request ({@code 503}). */
	OutputStream outputStream();

	/** The body, as UTF-8 text - the same buffer as {@link #outputStream()}. */
	PrintWriter writer();
}
