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
package org.frontcache.tests.combine;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads and resets {@link CombineCellServlet}'s counters over HTTP, because the e2e suite runs in a
 * different JVM from the servers.
 *
 * Deliberately uncacheable: no {@code x-frontcache-component-maxage}, so neither Frontcache node on
 * the path stores it and a test always reads the live number. It also sets
 * {@code Cache-Control: no-store} for anything else in the path.
 */
@SuppressWarnings("serial")
public class CombineCountersServlet extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

		if (null != request.getParameter("reset"))
		{
			CombineCellServlet.invocations.set(0);
			CombineCellServlet.combinedInvocations.set(0);
		}

		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write("invocations=" + CombineCellServlet.invocations.get()
				+ " combined=" + CombineCellServlet.combinedInvocations.get());
	}
}
