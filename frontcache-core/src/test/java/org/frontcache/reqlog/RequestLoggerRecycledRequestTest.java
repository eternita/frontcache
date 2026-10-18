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
package org.frontcache.reqlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.RequestContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A log line must never fail the request it describes.
 *
 * An include is logged from whichever thread resolved it, and an ASYNC include is resolved
 * fire-and-forget after the top-level response has completed - by which time the container has
 * recycled the servlet request. Jetty does not answer null for that: {@code getHeader} throws
 * {@code NullPointerException: ... ServletApiRequest.getRequest() is null}. That escaped as a
 * Throwable out of the include, which then resolved a fallback for a fragment it had ALREADY
 * fetched successfully - visible in the e2e run as
 * {@code Throwable: unexpected error processing include .../deep-include-async/e.jsp}.
 *
 * The same hazard {@code FCUtils.getClientIP} documents for the same reason, in the same place.
 */
public class RequestLoggerRecycledRequestTest {

	private Logger logger;
	private ListAppender<ILoggingEvent> appender;
	private Level previousLevel;

	@Before
	public void captureLog() {
		logger = (Logger) LoggerFactory.getLogger(RequestLogger.class);
		previousLevel = logger.getLevel();

		appender = new ListAppender<ILoggingEvent>();
		appender.start();

		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);
	}

	@After
	public void releaseLog() {
		logger.detachAppender(appender);
		logger.setLevel(previousLevel);
	}

	@Test
	public void aRecycledRequestStillProducesALine() {

		RequestContext context = new RequestContext();
		context.setRequest(recycledRequest());
		context.setRequestId("id-1");
		context.setDomain("www.example-site.com");
		context.setClientIP("203.0.113.7");

		RequestLogger.logRequest("https://www.example-site.com/fc/x.htm", true, false, 12, 34, context);

		List<ILoggingEvent> lines = appender.list;
		assertEquals("the line was not written", 1, lines.size());

		String line = lines.get(0).getFormattedMessage();
		assertTrue("the URL is missing: " + line, line.contains("https://www.example-site.com/fc/x.htm"));
		assertTrue("an unreadable method should read as \"-\": " + line, line.contains(" - "));
	}

	@Test
	public void anAbsentRequestStillProducesALine() {

		RequestContext context = new RequestContext();
		context.setRequestId("id-2");
		context.setDomain("www.example-site.com");
		context.setClientIP("203.0.113.7");

		RequestLogger.logRequest("https://www.example-site.com/fc/y.htm", true, true, 3, 4, context);

		assertEquals(1, appender.list.size());
		assertTrue(appender.list.get(0).getFormattedMessage().contains("from-cache"));
	}

	/** What Jetty gives back once the request has been recycled: every call throws. */
	private static HttpServletRequest recycledRequest() {
		return (HttpServletRequest) Proxy.newProxyInstance(
				RequestLoggerRecycledRequestTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						throw new NullPointerException("Cannot invoke \"org.eclipse.jetty.server.Request.getHeaders()\"");
					}
				});
	}
}
