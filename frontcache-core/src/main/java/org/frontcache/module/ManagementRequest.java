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
package org.frontcache.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A management call as a module sees it. It has already passed {@code ApiKey.isAuthorized}; a module
 * never sees an unauthenticated call and never re-implements the gate.
 */
public interface ManagementRequest {

	String action();

	/** A query-string or form parameter; null when absent. */
	String parameter(String name);

	/**
	 * The request body, unread. {@code getParameter} on a non-form POST reads the query string only, which
	 * is what leaves the body here to be streamed.
	 */
	InputStream body() throws IOException;

	/** Whether the management call arrived over TLS, as the container reports it. */
	boolean isSecure();

	/** The local port the management call arrived on. */
	int localPort();

	/**
	 * {@code ApiKey.isAuthorizedToWrite}: stricter than the read gate - it refuses on a node with no API
	 * key configured. A module's state-changing action must check it.
	 */
	boolean isAuthorizedToWrite();

	/**
	 * Answers with a raw body instead of JSON; the action must then return null.
	 *
	 * @param fileName  sent as an attachment's name when not null - the caller must have validated it
	 */
	OutputStream openRawOutput(String contentType, String fileName) throws IOException;
}
