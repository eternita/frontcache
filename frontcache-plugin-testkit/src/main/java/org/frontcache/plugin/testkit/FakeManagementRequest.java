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
package org.frontcache.plugin.testkit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.frontcache.plugin.ManagementRequest;

/**
 * A management call as a plugin's {@link org.frontcache.plugin.ManagementAction} sees it - past the API-key gate,
 * read-only unless {@link #writeAuthorized(boolean)}. A raw answer ({@link #openRawOutput}) is kept for the test.
 */
public final class FakeManagementRequest implements ManagementRequest {

	private final String action;
	private final Map<String, String> parameters = new LinkedHashMap<>();
	private byte[] body = new byte[0];
	private boolean secure;
	private int localPort = 9080;
	private boolean writeAuthorized;
	private ByteArrayOutputStream raw;
	private String rawContentType;
	private String rawFileName;

	private FakeManagementRequest(String action) {
		this.action = action;
	}

	public static FakeManagementRequest of(String action) {
		return new FakeManagementRequest(action);
	}

	public FakeManagementRequest parameter(String name, String value) {
		parameters.put(name, value);
		return this;
	}

	public FakeManagementRequest body(String body) {
		this.body = body.getBytes(StandardCharsets.UTF_8);
		return this;
	}

	public FakeManagementRequest secure(boolean secure) {
		this.secure = secure;
		return this;
	}

	public FakeManagementRequest localPort(int port) {
		this.localPort = port;
		return this;
	}

	/** Whether the caller passed the write gate - false by default, as for a read-only key. */
	public FakeManagementRequest writeAuthorized(boolean authorized) {
		this.writeAuthorized = authorized;
		return this;
	}

	@Override
	public String action() {
		return action;
	}

	@Override
	public String parameter(String name) {
		return parameters.get(name);
	}

	@Override
	public InputStream body() {
		return new ByteArrayInputStream(body);
	}

	@Override
	public boolean isSecure() {
		return secure;
	}

	@Override
	public int localPort() {
		return localPort;
	}

	@Override
	public boolean isAuthorizedToWrite() {
		return writeAuthorized;
	}

	@Override
	public OutputStream openRawOutput(String contentType, String fileName) {
		rawContentType = contentType;
		rawFileName = fileName;
		raw = new ByteArrayOutputStream();
		return raw;
	}

	/** The raw answer, or null when the action answered JSON. */
	public byte[] rawOutput() {
		return (null == raw) ? null : raw.toByteArray();
	}

	public String rawContentType() {
		return rawContentType;
	}

	public String rawFileName() {
		return rawFileName;
	}
}
