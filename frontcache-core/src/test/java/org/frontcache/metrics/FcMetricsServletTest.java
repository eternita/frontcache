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
package org.frontcache.metrics;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.frontcache.FCConfig;
import org.frontcache.core.FCHeaders;
import org.frontcache.resilience.FcTestSupport;
import org.junit.After;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The gate on {@code /fc-metrics}.
 *
 * One check, and it is the only boundary this endpoint ever had: the API key answers "who", and is
 * what keeps traffic volumes, error rates, circuit state and this node's heap off a public port. The
 * old {@code front-cache.management.port} check answered "which connector" and read a Host header the
 * caller supplies, so it never restricted anybody; it has been removed, and which connector answers
 * management traffic is the reverse proxy's job.
 *
 * The endpoint shipped with only that port check, which is the hole {@code apiKeyIsRequired} pins
 * shut.
 */
public class FcMetricsServletTest {

	private static final String API_KEY_KEY = "front-cache.api-key";

	/**
	 * Captured, not assumed to be empty. FCConfig is process-wide static state, and other tests in this
	 * fork read an API key loaded from their own FRONTCACHE_HOME - blanking it here failed two of them
	 * depending on test order, which is a nastier bug than the one this class is about.
	 */
	private final String originalApiKey = FCConfig.getProperty(API_KEY_KEY, "");

	@After
	public void tearDown()
	{
		FcMetricsExport.stop();
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FCConfig.setProperty(API_KEY_KEY, originalApiKey);
		FcTestSupport.resetAll();
	}

	private static HttpServletRequest request()
	{
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		return request;
	}

	private static HttpServletRequest request(String header, String value)
	{
		HttpServletRequest request = request();
		when(request.getHeader(header)).thenReturn(value);
		return request;
	}

	/**
	 * 404, not an empty 200. A scraper receiving nothing looks like a healthy target with no metrics,
	 * which is indistinguishable from the export being misconfigured.
	 */
	@Test
	public void notFoundWhenExportIsDisabled() throws Exception
	{
		FCConfig.setProperty(API_KEY_KEY, "");   // this test is about the export, so pin the other axis
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		new FcMetricsServlet().service(request(), response);

		verify(response).sendError(anyInt(), contains("not enabled"));
	}

	/** No API key configured keeps the historical behaviour: reachable on every connector. */
	@Test
	public void unrestrictedWhenNoApiKeyIsSet() throws Exception
	{
		FCConfig.setProperty(API_KEY_KEY, "");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		StringWriter body = new StringWriter();
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(body));

		new FcMetricsServlet().service(request(), response);

		verify(response).setContentType(contains("version=0.0.4"));
		verify(response, never()).sendError(anyInt(), anyString());
	}

	// ---- the API key, which is the actual access control ----------------------------------------

	/**
	 * The hole this closes. The removed port check compared {@code getServerPort()}, which the servlet
	 * spec derives from the Host header - so a caller who could reach ANY connector could ask for the
	 * management port and be served the whole scrape. Every other management endpoint required an API
	 * key underneath that check; this one did not.
	 */
	@Test
	public void apiKeyIsRequired() throws Exception
	{
		FCConfig.setProperty(API_KEY_KEY, "s3cret");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		new FcMetricsServlet().service(request(), response);

		verify(response).sendError(eq(401), contains("api-key"));
		verify(response, never()).getWriter();
	}

	/** Still accepted for one release - an embedded agent or an older console may send it. */
	@Test
	@SuppressWarnings("deprecation")
	public void theDeprecatedHeaderIsStillAccepted() throws Exception
	{
		FCConfig.setProperty(API_KEY_KEY, "s3cret");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

		new FcMetricsServlet().service(request(FCHeaders.X_FRONTCACHE_SITE_KEY, "s3cret"), response);

		verify(response, never()).sendError(anyInt(), anyString());
	}

	/**
	 * Prometheus cannot send an arbitrary request header - a scrape_config offers basic_auth,
	 * authorization and oauth2 and nothing else - so the Bearer form is not a nicety. Without it,
	 * requiring an API key here would make the endpoint unscrapable by the tool it exists for.
	 */
	@Test
	public void authorizationBearerIsAcceptedBecausePrometheusCannotSendAHeader() throws Exception
	{
		FCConfig.setProperty(API_KEY_KEY, "s3cret");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

		new FcMetricsServlet().service(request("Authorization", "Bearer s3cret"), response);

		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	public void aWrongKeyIsRefused() throws Exception
	{
		FCConfig.setProperty(API_KEY_KEY, "s3cret");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		new FcMetricsServlet().service(request("Authorization", "Bearer not-the-key"), response);

		verify(response).sendError(eq(401), anyString());
	}
}
