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
package org.frontcache.plugin.host;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;

/** A request and a response good enough for the guard and /fc-x/: what the tests set, and what was written. */
public final class ServletFakes {

	private ServletFakes() {
	}

	/** A request a test describes; {@link #context()} builds the RequestContext the engine would. */
	public static final class Request {
		String method = "GET";
		String uri = "/";
		String query = "";
		String remoteAddr = "203.0.113.7";
		String requestType = FCHeaders.COMPONENT_TOPLEVEL;
		String scheme = "https";
		final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		final Map<String, String> cookies = new LinkedHashMap<>();
		byte[] body = new byte[0];
		boolean declareLength = true;

		public Request method(String value) {
			method = value;
			return this;
		}

		/** @param pathAndQuery {@code /search?q=x} */
		public Request uri(String pathAndQuery) {
			int q = pathAndQuery.indexOf('?');
			uri = (-1 < q) ? pathAndQuery.substring(0, q) : pathAndQuery;
			query = (-1 < q) ? pathAndQuery.substring(q) : "";
			return this;
		}

		public Request from(String address) {
			remoteAddr = address;
			return this;
		}

		public Request type(String value) {
			requestType = value;
			return this;
		}

		public Request header(String name, String value) {
			headers.put(name, value);
			return this;
		}

		public Request cookie(String name, String value) {
			cookies.put(name, value);
			return this;
		}

		public Request body(String contentType, String value, boolean declareLength) {
			headers.put("Content-Type", contentType);
			body = value.getBytes(StandardCharsets.UTF_8);
			this.declareLength = declareLength;
			return this;
		}

		public RequestContext context() {
			return context(new Response());
		}

		public RequestContext context(Response response) {
			RequestContext context = new RequestContext();
			context.setRequest(servlet());
			context.setResponse(response.servlet());
			context.setRequestURI(uri);
			context.setRequestQueryString(query);
			context.setRequestType(requestType);
			context.setRequestId("req-1");
			context.setFrontCacheHost("www.example.com");
			context.setFrontCacheProtocol(scheme);
			context.setPublicProtocol(scheme);
			context.setClientType("guest");
			return context;
		}

		HttpServletRequest servlet() {
			ByteArrayInputStream in = new ByteArrayInputStream(body);
			ServletInputStream stream = new ServletInputStream() {
				@Override
				public int read() {
					return in.read();
				}

				@Override
				public int read(byte[] b, int off, int len) {
					return in.read(b, off, len);
				}

				@Override
				public boolean isFinished() {
					return 0 == in.available();
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
				}
			};
			return (HttpServletRequest) Proxy.newProxyInstance(ServletFakes.class.getClassLoader(),
					new Class[] { HttpServletRequest.class }, (proxy, m, args) -> {
						switch (m.getName())
						{
						case "getMethod":
							return method;
						case "getRemoteAddr":
							return remoteAddr;
						case "getHeader":
							return headers.get((String) args[0]);
						case "getHeaders":
							String v = headers.get((String) args[0]);
							return Collections.enumeration((null == v) ? List.<String>of() : List.of(v));
						case "getHeaderNames":
							return Collections.enumeration(new ArrayList<>(headers.keySet()));
						case "getCookies":
							if (cookies.isEmpty())
								return null;
							List<Cookie> list = new ArrayList<>();
							cookies.forEach((n, val) -> list.add(new Cookie(n, val)));
							return list.toArray(new Cookie[0]);
						case "getContentLengthLong":
							return declareLength ? (long) body.length : -1L;
						case "getContentLength":
							return declareLength ? body.length : -1;
						case "getInputStream":
							return stream;
						case "getScheme":
							return scheme;
						case "getServerPort":
							return 443;
						case "toString":
							return "fake " + method + " " + uri;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						default:
							return null;
						}
					});
		}
	}

	/** What the node wrote. */
	public static final class Response {
		public int status = 200;
		public final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		public final ByteArrayOutputStream body = new ByteArrayOutputStream();

		public String header(String name) {
			List<String> values = headers.get(name);
			return (null == values || values.isEmpty()) ? null : values.get(0);
		}

		public String text() {
			return body.toString(StandardCharsets.UTF_8);
		}

		HttpServletResponse servlet() {
			ServletOutputStream out = new ServletOutputStream() {
				@Override
				public void write(int b) {
					body.write(b);
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setWriteListener(WriteListener listener) {
				}
			};
			return (HttpServletResponse) Proxy.newProxyInstance(ServletFakes.class.getClassLoader(),
					new Class[] { HttpServletResponse.class }, (proxy, m, args) -> {
						switch (m.getName())
						{
						case "setStatus":
							status = (Integer) args[0];
							return null;
						case "setHeader":
							headers.put((String) args[0], new ArrayList<>(List.of((String) args[1])));
							return null;
						case "addHeader":
							headers.computeIfAbsent((String) args[0], k -> new ArrayList<>()).add((String) args[1]);
							return null;
						case "setContentType":
							headers.put("Content-Type", new ArrayList<>(List.of((String) args[0])));
							return null;
						case "setContentLength":
							headers.put("Content-Length", new ArrayList<>(List.of(String.valueOf(args[0]))));
							return null;
						case "getOutputStream":
							return out;
						case "isCommitted":
							return false;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						default:
							return null;
						}
					});
		}
	}
}
