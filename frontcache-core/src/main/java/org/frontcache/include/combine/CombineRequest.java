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
package org.frontcache.include.combine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The origin's half of the combine contract: read a combined request.
 *
 * This is not used by the edge. It exists so a JVM origin implements the contract by calling the
 * same code that defines it, rather than by re-reading docs/include-combining.md and getting the
 * parameter names nearly right. A non-JVM origin implements about thirty lines - decode n values,
 * batch, emit JSON.
 *
 * Typical handler:
 *
 * <pre>
 * List&lt;Map&lt;String, String[]&gt;&gt; parts = CombineRequest.parseParameters(request);
 * if (null == parts)
 *     return renderSingle(request);            // ordinary single-fragment request
 *
 * // ONE batched query over every part's keys - this is the entire point of the feature
 * Map&lt;Long, Coin&gt; coins = dao.getByIds(idsOf(parts));
 *
 * CombineResponse out = new CombineResponse();
 * for (Map&lt;String, String[]&gt; part : parts)
 *     out.add(CombineRequest.queryOf(part), render(coins, part), headersFor(part));
 * out.writeTo(response);
 * </pre>
 */
public final class CombineRequest {

	private CombineRequest() {
	}

	/** Is this a combined request? Cheap enough to call at the top of a handler. */
	public static boolean isCombined(HttpServletRequest request) {
		return null != request.getParameter(CombineProtocol.PARAM_COMBINE);
	}

	/**
	 * The protocol version the edge is speaking, or -1 when this is not a combined request. An origin
	 * that only implements version 1 should answer a higher version as it would answer an ordinary
	 * single request - the edge then degrades correctly instead of receiving
	 * an envelope it cannot read.
	 */
	public static int version(HttpServletRequest request) {
		return intParam(request, CombineProtocol.PARAM_COMBINE, -1);
	}

	/**
	 * The members' query strings, decoded, in order. {@code null} when this is not a combined
	 * request, so a handler can branch on it directly.
	 *
	 * A missing {@code fc-part-i} is returned as an empty string rather than dropped: the response
	 * is positional, so silently shortening the list would misalign every later member.
	 */
	public static List<String> parseQueries(HttpServletRequest request) {

		if (!isCombined(request))
			return null;

		int count = intParam(request, CombineProtocol.PARAM_PARTS, -1);
		if (0 > count)
			return null;

		List<String> queries = new ArrayList<String>(count);
		for (int i = 0; i < count; i++)
		{
			// Already decoded by the container - the member query travelled as one parameter VALUE.
			String q = request.getParameter(CombineProtocol.PARAM_PART + i);
			queries.add(null == q ? "" : q);
		}

		return queries;
	}

	/**
	 * The members' parameters, parsed, in order. {@code null} when this is not a combined request.
	 *
	 * The maps preserve parameter order and support repeated parameters, so a member query behaves
	 * the way {@code request.getParameterMap()} would have behaved for a single include.
	 */
	public static List<Map<String, String[]>> parseParameters(HttpServletRequest request) {

		List<String> queries = parseQueries(request);
		if (null == queries)
			return null;

		List<Map<String, String[]>> parsed = new ArrayList<Map<String, String[]>>(queries.size());
		for (String query : queries)
			parsed.add(parseQueryString(query));

		return parsed;
	}

	/**
	 * Splits one member's query string into parameters. Values are percent-decoded with UTF-8.
	 *
	 * Written out rather than delegated to the container because the member query arrived as a
	 * value: by the time it is in hand there is no request to ask.
	 */
	public static Map<String, String[]> parseQueryString(String query) {

		Map<String, String[]> params = new LinkedHashMap<String, String[]>();

		if (null == query || query.isEmpty())
			return params;

		for (String pair : query.split("&"))
		{
			if (pair.isEmpty())
				continue;

			int eqIdx = pair.indexOf('=');
			String name  = (-1 == eqIdx) ? pair : pair.substring(0, eqIdx);
			String value = (-1 == eqIdx) ? ""   : pair.substring(eqIdx + 1);

			name  = CombineProtocol.decodePart(name);
			value = CombineProtocol.decodePart(value);

			String[] existing = params.get(name);
			if (null == existing)
			{
				params.put(name, new String[] { value });
			} else {
				String[] grown = new String[existing.length + 1];
				System.arraycopy(existing, 0, grown, 0, existing.length);
				grown[existing.length] = value;
				params.put(name, grown);
			}
		}

		return params;
	}

	/**
	 * First value of a parameter within one parsed member, or null.
	 * The per-member equivalent of {@code request.getParameter}.
	 */
	public static String param(Map<String, String[]> part, String name) {

		String[] values = part.get(name);
		return (null == values || 0 == values.length) ? null : values[0];
	}

	/**
	 * Re-renders a parsed member back into a query string, for the optional {@code q} echo
	 * ({@link CombinePart#getQ()}). Handlers that kept {@link #parseQueries}' strings should echo
	 * those instead - this is for handlers that only kept the parsed form.
	 */
	public static String queryOf(Map<String, String[]> part) {

		StringBuilder sb = new StringBuilder(64);
		for (Map.Entry<String, String[]> entry : part.entrySet())
			for (String value : entry.getValue())
			{
				if (0 < sb.length())
					sb.append('&');
				sb.append(CombineProtocol.encodePart(entry.getKey())).append('=')
				  .append(CombineProtocol.encodePart(value));
			}

		return sb.toString();
	}

	/** Unmodifiable empty member, for a handler that wants a non-null default. */
	public static Map<String, String[]> emptyPart() {
		return Collections.emptyMap();
	}

	private static int intParam(HttpServletRequest request, String name, int defaultValue) {

		String raw = request.getParameter(name);
		if (null == raw)
			return defaultValue;

		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
