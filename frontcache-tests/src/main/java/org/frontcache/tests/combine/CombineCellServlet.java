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
package org.frontcache.tests.combine;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.core.FCHeaders;
import org.frontcache.include.combine.CombineProtocol;
import org.frontcache.include.combine.CombineRequest;
import org.frontcache.include.combine.CombineResponse;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A combine-aware origin endpoint, of the shape a real one has: one branch for an ordinary single
 * fragment, one for a batch, and ONE render pass over whatever it was given.
 *
 * The point the e2e tests read off it is {@link #invocations}: a page of N combinable cells must
 * reach this servlet once, not N times. That is the entire feature - a real origin's saving is the
 * batched DB/Solr query behind this counter, which a test webapp has nothing to stand in for.
 *
 * Also serves the two deliberately-broken variants (docs/archive/combine-reduce-proposal.md section 9),
 * selected by path so a test can point a page at one of them:
 *
 * <ul>
 * <li>{@code /common/combine/cell}             - implements the contract;</li>
 * <li>{@code /common/combine/cell-unsupported*} - ignores fc-combine, answers as if single. Numbered
 *     variants exist because the edge memoises "this group has no combine handler" for a minute:
 *     two tests exercising that would otherwise inherit each other's memo;</li>
 * <li>{@code /common/combine/cell-short}       - returns an envelope one part short;</li>
 * <li>{@code /common/combine/cell-shuffled}    - returns the parts in reverse order.</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class CombineCellServlet extends HttpServlet {

	/** Origin invocations of this endpoint - what the tests count. Reset via CombineCountersServlet. */
	public static final AtomicInteger invocations = new AtomicInteger();

	/** Combined invocations only, so a test can tell "one batch" from "one single fragment". */
	public static final AtomicInteger combinedInvocations = new AtomicInteger();

	static final String MAX_AGE = "1h";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

		invocations.incrementAndGet();

		String mode = mode(request);

		List<String> queries = CombineRequest.parseQueries(request);

		if (null == queries || mode.startsWith("unsupported"))
		{
			// Not a combined request - or an origin that does not implement combining and therefore
			// cannot tell the difference. Either way: answer with one fragment.
			writeFragment(response, idOf(request.getParameter("id")));
			return;
		}

		combinedInvocations.incrementAndGet();

		CombineResponse out = new CombineResponse();

		// The single render pass. A real origin runs its page-level batch here - one IN (...) over
		// every member's key - which is the whole reason the edge went to the trouble of asking once.
		for (String query : queries)
		{
			Map<String, String[]> params = CombineRequest.parseQueryString(query);
			out.addComponent(query, cell(idOf(CombineRequest.param(params, "id"))), MAX_AGE, "cell");
		}

		if ("short".equals(mode))
			// One part fewer than asked for - a protocol error the edge must catch rather than
			// splice a page whose cells have shifted by one.
			out.getEnvelope().getParts().remove(out.size() - 1);

		if ("shuffled".equals(mode))
			java.util.Collections.reverse(out.getEnvelope().getParts());

		out.writeTo(response);
	}

	private static String mode(HttpServletRequest request) {

		String path = request.getRequestURI();
		int idx = path.lastIndexOf("/cell-");

		return (-1 == idx) ? "ok" : path.substring(idx + "/cell-".length());
	}

	private static String idOf(String id) {
		return (null == id) ? "?" : id;
	}

	private static String cell(String id) {
		return "[cell-" + id + "]";
	}

	/** The single-fragment answer, cacheable on exactly the terms a combined part is. */
	private static void writeFragment(HttpServletResponse response, String id) throws IOException {

		response.setContentType("text/html;charset=UTF-8");
		response.setHeader(FCHeaders.X_FRONTCACHE_COMPONENT, "true");
		response.setHeader(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, MAX_AGE);
		response.setHeader(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, "cell");
		response.getWriter().write(cell(id));
	}

	/** Only used by the tests' own assertions, to spell the expected body the same way once. */
	public static String expectedCell(String id) {
		return cell(id);
	}

	/** Belt and braces: the version the edge speaks, so a mismatch shows up as a test failure. */
	public static int protocolVersion() {
		return CombineProtocol.VERSION;
	}
}
