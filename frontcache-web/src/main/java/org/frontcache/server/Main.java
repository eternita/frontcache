package org.frontcache.server;

import java.io.File;
import java.io.FileNotFoundException;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.frontcache.FrontCacheServlet;

public class Main {

	public Main() {
		// TODO Auto-generated constructor stub
	}

	public static void main(String[] args) throws Exception {

		int httpPort = 8083;
		int httpsPort = 8043;
		String jettyDistKeystore = "/Users/spa/Development/tools/keystore";
		String keystorePath = System.getProperty("example.keystore", jettyDistKeystore);
		File keystoreFile = new File(keystorePath);
		if (!keystoreFile.exists()) {
			throw new FileNotFoundException(keystoreFile.getAbsolutePath());
		}

//        // Path to as-built jetty-distribution directory
//        String jettyHomeBuild = "/Users/spa/git/frontcache/frontcache-core/dist.srt/jetty";
//         
//        // Find jetty home and base directories
//        String homePath = System.getProperty("jetty.home", jettyHomeBuild);
//        File homeDir = new File(homePath);
//        if (!homeDir.exists())
//        {
//            throw new FileNotFoundException(homeDir.getAbsolutePath());
//        }
//        String basePath = System.getProperty("jetty.base", homeDir + "/demo-base");
//        File baseDir = new File(basePath);
//        if(!baseDir.exists())
//        {
//            throw new FileNotFoundException(baseDir.getAbsolutePath());
//        }
         
        // Configure jetty.home and jetty.base system properties
//        String jetty_home = homeDir.getAbsolutePath();
//        String jetty_base = baseDir.getAbsolutePath();
//        System.setProperty("jetty.home", jetty_home);
//        System.setProperty("jetty.base", jetty_base);
 
 
        // === jetty.xml ===
        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);
 		
		Server server = new Server();

		// HTTP Configuration
		HttpConfiguration http_config = new HttpConfiguration();
		http_config.setSecureScheme("https");
		http_config.setSecurePort(httpsPort);
		http_config.setOutputBufferSize(32768);

		// HTTP connector
		ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(http_config));
		http.setPort(httpPort);
		http.setIdleTimeout(30000);

		// SSL Context Factory for HTTPS
		SslContextFactory sslContextFactory = new SslContextFactory();
		sslContextFactory.setKeyStorePath(keystoreFile.getAbsolutePath());
		sslContextFactory.setKeyStorePassword("changeit");
		sslContextFactory.setKeyManagerPassword("changeit");

		// HTTPS Configuration
		HttpConfiguration https_config = new HttpConfiguration(http_config);
		SecureRequestCustomizer src = new SecureRequestCustomizer();
		// src.setStsMaxAge(2000);
		// src.setStsIncludeSubDomains(true);
		https_config.addCustomizer(src);

		// HTTPS connector
		ServerConnector https = new ServerConnector(server,
				new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
				new HttpConnectionFactory(https_config));
		https.setPort(httpsPort);
		https.setIdleTimeout(500000);

		// Set the connectors
		server.setConnectors(new Connector[] { http, https });

		ServletHandler handler = new ServletHandler();
		server.setHandler(handler);

		handler.addServletWithMapping(FrontCacheServlet.class, "/*");

		server.start();
		server.join();

	}

}
