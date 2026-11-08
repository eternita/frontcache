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
package org.frontcache.console;

import java.io.File;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Embedded Jetty 12 (Jakarta EE10) launcher for running the Frontcache console
 * locally. Deploys the webapp at context "/" unless told otherwise.
 * Kept self-contained in this module so the console does not depend on the
 * frontcache-server project.
 *
 * System properties:
 *   frontcache.jetty.port          - listen port (default 7080)
 *   frontcache.jetty.webapp        - path to the exploded webapp directory (or war)
 *   frontcache.jetty.host          - the address to listen on (default: every interface). 127.0.0.1
 *                                    for a console reached only through a reverse proxy on the same host
 *   frontcache.jetty.context-path  - the context path (default "/"), as of 2.12.0 - for a console
 *                                    served under a path of a larger site, e.g. /ops/console. The
 *                                    console's pages, scripts and redirects are context-relative, so a
 *                                    proxy forwards the path unchanged rather than rewriting it
 */
public class ConsoleJettyServer {

	public static void main(String[] args) throws Exception {

		int port = Integer.parseInt(System.getProperty("frontcache.jetty.port", "7080"));
		String webDir = System.getProperty("frontcache.jetty.webapp");
		String host = System.getProperty("frontcache.jetty.host", "").trim();
		String contextPath = contextPath(System.getProperty("frontcache.jetty.context-path"));

		System.out.println("Starting Frontcache Console Jetty Server (port=" + port
				+ (host.isEmpty() ? "" : ", host=" + host) + ", context-path=" + contextPath + ", webapp=" + webDir + ") ...");

		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setPort(port);
		if (!host.isEmpty())
			connector.setHost(host);
		server.addConnector(connector);

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath(contextPath);
		webapp.setWar(new File(webDir).getAbsolutePath());
		webapp.setThrowUnavailableOnStartupException(true);

		// Scan container jars for ServletContainerInitializers (JSP/JSTL support).
		// The JSP/JSTL impls live in container jars, so their META-INF TLDs are only
		// found if the jars are named in the container-scan pattern. When running from
		// the exploded webapp (appRun) WEB-INF/lib is empty, so spring-webmvc (which
		// carries META-INF/spring-form.tld + spring.tld) is a container-classpath jar
		// too and must be listed here or the Spring form taglib URI won't resolve.
		//
		// frontcache.jetty.container-jars adds to the pattern: a console plugin jar on the container class
		// path (a plugin's dev run, which serves the exploded webapp) carries its JSPs under
		// META-INF/resources, and Jetty serves those only from a jar the pattern names. A plugin jar in
		// the war's WEB-INF/lib needs nothing - Jetty scans every one of those.
		String extraJars = System.getProperty("frontcache.jetty.container-jars", "").trim();
		webapp.addConfiguration(new AnnotationConfiguration());
		webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
				".*/jakarta\\.servlet\\.jsp\\.jstl-.*\\.jar$|"
				+ ".*/org\\.glassfish\\.web\\..*\\.jar$|"
				+ ".*/jetty-ee10-apache-jsp-.*\\.jar$|"
				+ ".*/org\\.mortbay\\.jasper\\..*\\.jar$|"
				+ ".*/jetty-ee10-glassfish-jstl-.*\\.jar$|"
				+ ".*/spring-webmvc-.*\\.jar$|"
				+ ".*/taglibs.*\\.jar$"
				+ (extraJars.isEmpty() ? "" : "|" + extraJars));

		server.setHandler(webapp);
		server.start();

		System.out.println("Frontcache Console Jetty Server has been started successfully ...");

		server.join();
	}

	/**
	 * {@code "/"} for nothing, else one leading slash and no trailing one - what Jetty wants, from what
	 * an operator is likely to type ({@code console/}, {@code /console/}).
	 */
	static String contextPath(String configured) {

		String path = null == configured ? "" : configured.trim();

		while (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);

		if (path.isEmpty())
			return "/";

		return path.startsWith("/") ? path : "/" + path;
	}

}
