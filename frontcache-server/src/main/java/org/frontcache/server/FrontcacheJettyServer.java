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

import java.io.File;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Embedded Jetty 12 (Jakarta EE10) launcher for running Frontcache standalone
 * locally. Deploys the webapp at context "/".
 *
 * System properties:
 *   frontcache.jetty.port    - listen port (default 9080)
 *   frontcache.jetty.webapp  - path to the exploded webapp directory (or war)
 */
public class FrontcacheJettyServer {

	public static void main(String[] args) throws Exception {

		int port = Integer.parseInt(System.getProperty("frontcache.jetty.port", "9080"));
		String webDir = System.getProperty("frontcache.jetty.webapp");

		System.out.println("Starting Frontcache Jetty Server (port=" + port + ", webapp=" + webDir + ") ...");

		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration()));
		connector.setPort(port);
		server.addConnector(connector);

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath("/");
		webapp.setWar(new File(webDir).getAbsolutePath());
		webapp.setThrowUnavailableOnStartupException(true);

		// Scan container jars for ServletContainerInitializers (JSP/JSTL support).
		// The JSP/JSTL impls live in container jars, so their META-INF TLDs are only
		// found if the jars are named in the container-scan pattern.
		webapp.addConfiguration(new AnnotationConfiguration());
		webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
				".*/jakarta\\.servlet\\.jsp\\.jstl-.*\\.jar$|"
				+ ".*/org\\.glassfish\\.web\\..*\\.jar$|"
				+ ".*/jetty-ee10-apache-jsp-.*\\.jar$|"
				+ ".*/org\\.mortbay\\.jasper\\..*\\.jar$|"
				+ ".*/jetty-ee10-glassfish-jstl-.*\\.jar$|"
				+ ".*/taglibs.*\\.jar$");

		server.setHandler(webapp);
		server.start();

		System.out.println("Frontcache Jetty Server has been started successfully ...");

		server.join();
	}

	/**
	 * The connector's HTTP configuration - public so a test can serve through exactly what a node serves through.
	 *
	 * It honours {@code X-Forwarded-Proto} / {@code -Port} / {@code -Host} from the nginx front door, so that
	 * getScheme(), isSecure() and getServerPort() report the client-facing values (https/443) rather than the
	 * plain-HTTP backend hop. Without that, absolute-URL and redirect generation sees port 80 and breaks behind a
	 * TLS-terminating proxy.
	 *
	 * It must NEVER let a forwarding header set the client address. Jetty's {@link ForwardedRequestCustomizer}
	 * does that by default, from {@code X-Forwarded-For} and from RFC 7239 {@code Forwarded: for=}. It replaces
	 * getRemoteAddr() with the header's LEFTMOST entry - the one the client wrote - and it does so from any peer.
	 * {@code ClientIpResolver} then took that client-chosen value for the socket peer, found it untrusted, and
	 * returned it. Its right-to-left walk over {@code front-cache.client-ip.trusted-proxies} never ran. So on
	 * every server-mode node behind a proxy, {@code client-ip:} rules, the {@code rate:} limiters, the evidence
	 * cookies' address binding, a trap rule's log reason and anything banning on the resolved address were
	 * keyed on whatever the client sent. {@code ClientIpResolver} is the one place that turns forwarding headers
	 * into a client address, with the trust list the node actually has (it is re-read on a reload; this launcher
	 * runs outside the war and could only hold a copy). So both headers are switched off here, and
	 * getRemoteAddr() stays the real socket peer. A blank header name means Jetty installs no handler for it.
	 * The headers themselves still reach the webapp untouched - the resolver reads them.
	 */
	public static HttpConfiguration httpConfiguration()
	{
		ForwardedRequestCustomizer forwarded = new ForwardedRequestCustomizer();
		forwarded.setForwardedForHeader(null);   // X-Forwarded-For: the client address is ClientIpResolver's
		forwarded.setForwardedHeader(null);      // RFC 7239 Forwarded: its for= sets the remote address too

		HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.addCustomizer(forwarded);
		return httpConfig;
	}

}
