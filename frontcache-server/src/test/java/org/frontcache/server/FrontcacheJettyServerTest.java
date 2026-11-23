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
package org.frontcache.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.frontcache.core.ClientIpResolver;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The launcher's connector, served through for real: what a webapp behind {@link FrontcacheJettyServer} sees of
 * a request's address and scheme, and what {@link ClientIpResolver} makes of it.
 *
 * The e2e suite could not catch the leftmost-X-Forwarded-For bug this pins. Its standalone node is the test
 * fixture {@code FrontcacheStandaloneServer}, which builds a bare {@code new Server(port)} with no customizer,
 * so it never ran the connector a shipped node runs. Measured on a production node behind nginx, with
 * {@code trusted-proxies=127.0.0.1/32}: nginx passed {@code 10.0.0.5, <real>} and the node resolved
 * {@code 10.0.0.5}.
 */
public class FrontcacheJettyServerTest {

	private static Server server;
	private static int port;

	/** what the webapp sees, one {@code key=value} per line */
	public static class EchoServlet extends HttpServlet {
		private static final long serialVersionUID = 1L;

		@Override
		protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			StringBuilder out = new StringBuilder();
			out.append("remote=").append(req.getRemoteAddr()).append('\n');
			out.append("scheme=").append(req.getScheme()).append('\n');
			out.append("secure=").append(req.isSecure()).append('\n');
			out.append("port=").append(req.getServerPort()).append('\n');
			out.append("host=").append(req.getServerName()).append('\n');
			out.append("xff=").append(req.getHeader("X-Forwarded-For")).append('\n');
			// the node's own resolver, as a node behind a loopback nginx configures it - and as one with no list
			out.append("resolved=").append(new ClientIpResolver("127.0.0.1/32,::1/128", null).resolve(req)).append('\n');
			out.append("resolvedUntrusted=").append(new ClientIpResolver("", null).resolve(req)).append('\n');
			resp.setContentType("text/plain");
			resp.getOutputStream().write(out.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	@BeforeClass
	public static void start() throws Exception {
		server = new Server();
		ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(FrontcacheJettyServer.httpConfiguration()));
		connector.setHost("127.0.0.1");
		connector.setPort(0);
		server.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addServlet(new ServletHolder(new EchoServlet()), "/*");
		server.setHandler(context);
		server.start();
		port = connector.getLocalPort();
	}

	@AfterClass
	public static void stop() throws Exception {
		if (null != server)
			server.stop();
	}

	@Test
	public void aForgedXForwardedForIsNotTheRemoteAddress() throws Exception {
		Map<String, String> seen = get("X-Forwarded-For: 203.0.113.1, 198.51.100.2");

		assertEquals("the socket peer, never a header", "127.0.0.1", seen.get("remote"));
		assertEquals("the header still reaches the webapp - the resolver reads it", "203.0.113.1, 198.51.100.2", seen.get("xff"));
		assertEquals("from a trusted peer: the rightmost untrusted entry, what nginx saw", "198.51.100.2", seen.get("resolved"));
		assertEquals("with no trust list the headers are ignored", "127.0.0.1", seen.get("resolvedUntrusted"));
	}

	/** the production measurement, row by row: before the fix the first two resolved to the forged entry */
	@Test
	public void theResolverTakesWhatTheTrustedProxyAppended() throws Exception {
		assertEquals("198.51.100.2", get("X-Forwarded-For: 10.0.0.5, 198.51.100.2").get("resolved"));
		assertEquals("198.51.100.2", get("X-Forwarded-For: 203.0.113.1, 203.0.113.9, 198.51.100.2").get("resolved"));
		assertEquals("198.51.100.2", get("X-Forwarded-For: 127.0.0.1, 198.51.100.2").get("resolved"));
	}

	@Test
	public void anRfc7239ForwardedForIsNotTheRemoteAddress() throws Exception {
		Map<String, String> seen = get("Forwarded: for=203.0.113.1;proto=https");

		assertEquals("127.0.0.1", seen.get("remote"));
		assertEquals("127.0.0.1", seen.get("resolved"));
	}

	/** why the customizer is there at all - TLS terminated in front, plain HTTP to the node */
	@Test
	public void xForwardedProtoAndPortStillDescribeTheClientFacingRequest() throws Exception {
		Map<String, String> seen = get("X-Forwarded-Proto: https", "X-Forwarded-Port: 443");

		assertEquals("https", seen.get("scheme"));
		assertEquals("true", seen.get("secure"));
		assertEquals("443", seen.get("port"));
		assertEquals("www.example.com", seen.get("host"));
		assertEquals("127.0.0.1", seen.get("remote"));
	}

	/** a raw HTTP/1.1 GET from loopback - an HTTP client library would choose some headers for us */
	private static Map<String, String> get(String... headers) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			StringBuilder request = new StringBuilder("GET /echo HTTP/1.1\r\nHost: www.example.com\r\nConnection: close\r\n");
			for (String header : headers)
				request.append(header).append("\r\n");
			request.append("\r\n");

			OutputStream out = socket.getOutputStream();
			out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
			out.flush();

			InputStream in = socket.getInputStream();
			String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(response, response.startsWith("HTTP/1.1 200"));

			Map<String, String> seen = new LinkedHashMap<>();
			String body = response.substring(response.indexOf("\r\n\r\n") + 4);
			for (String line : body.split("\n")) {
				int eq = line.indexOf('=');
				if (0 < eq)
					seen.put(line.substring(0, eq), line.substring(eq + 1).trim());
			}
			return seen;
		}
	}
}
