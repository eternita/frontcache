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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The combine-and-reduce wire contract, in one place: parameter names, the encoding of a member's
 * query string, and the small URL arithmetic both ends need.
 *
 * See docs/include-combining.md. Both halves of the contract live in this
 * package so an origin embedding frontcache-core cannot drift from the edge - see
 * {@link CombineRequest} and {@link CombineResponse}, which are the origin's half.
 *
 * <h3>Request</h3>
 *
 * <pre>
 * GET {path}?fc-combine=1&amp;fc-parts={n}&amp;fc-part-0={q0}&amp;fc-part-1={q1}&amp;...
 * </pre>
 *
 * where q(i) is member i's complete original query string (no leading {@code ?}), percent-encoded
 * as a single value.
 *
 * <h3>Why the parts are indexed rather than repeated</h3>
 *
 * {@code fc-part=a&fc-part=b} would be shorter, and would make the origin depend on its container
 * preserving the order of {@code getParameterValues} - which the servlet spec does not require.
 * The response is positional against i, so an ordering the origin cannot rely on is exactly the
 * wrong thing to build the contract from. With {@code fc-part-{i}} the origin reads a known index
 * and the question does not arise.
 *
 * <h3>Why a member's query is carried as one encoded value</h3>
 *
 * Because it is injection-safe by construction: a member's query is a <em>value</em>, never
 * concatenated into the query grammar, so no include URL can smuggle a parameter into the combined
 * request or into a sibling member. It is also the only shape that survives members differing in
 * more than one parameter, or in which parameters they carry at all - which is why
 * {@code ?id=1|2|3} was not taken.
 */
public final class CombineProtocol {

	/** Protocol version. Sent as the value of {@link #PARAM_COMBINE} and echoed in {@link #HEADER_COMBINE}. */
	public static final int VERSION = 1;

	/**
	 * Reserved query-parameter prefix. An include whose own query carries a parameter starting with
	 * this is never combined - the edge refuses rather than risk a collision the origin cannot detect.
	 */
	public static final String PARAM_PREFIX = "fc-";

	/** Presence makes a request a combined request; the value is {@link #VERSION}. */
	public static final String PARAM_COMBINE = "fc-combine";

	/** Number of members. Lets the origin size its batch before parsing, and the edge check the answer. */
	public static final String PARAM_PARTS = "fc-parts";

	/** Prefix of the per-member parameter: {@code fc-part-0}, {@code fc-part-1}, ... */
	public static final String PARAM_PART = "fc-part-";

	/**
	 * Response header. Its presence on a 2xx is the ONLY signal that the origin understood the
	 * request - an origin that does not implement combining answers something else, and the edge
	 * degrades. A header rather than a media type because {@code x-frontcache-*}
	 * names are already chosen to survive every intermediary in the deployment (see FCHeaders).
	 */
	public static final String HEADER_COMBINE = "x-frontcache-combine";

	public static final String ENVELOPE_CONTENT_TYPE = "application/json;charset=UTF-8";

	private CombineProtocol() {
	}

	/**
	 * The combined query string, including the leading {@code ?}.
	 *
	 * @param memberQueries each member's query string without a leading {@code ?}; null is treated as empty
	 */
	public static String buildCombinedQuery(List<String> memberQueries) {

		StringBuilder sb = new StringBuilder(64 + memberQueries.size() * 32);
		sb.append('?').append(PARAM_COMBINE).append('=').append(VERSION)
		  .append('&').append(PARAM_PARTS).append('=').append(memberQueries.size());

		for (int i = 0; i < memberQueries.size(); i++)
			sb.append('&').append(PARAM_PART).append(i).append('=').append(encodePart(memberQueries.get(i)));

		return sb.toString();
	}

	/**
	 * Percent-encodes one member query string for transport as a single parameter value.
	 *
	 * {@code application/x-www-form-urlencoded} (space as {@code +}), which is what every servlet
	 * container decodes query parameters as - so the origin's own {@code getParameter} hands the
	 * member's query back already decoded.
	 */
	public static String encodePart(String query) {

		if (null == query || query.isEmpty())
			return "";

		try {
			return URLEncoder.encode(query, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException("UTF-8 is not optional", e); // unreachable
		}
	}

	/** Inverse of {@link #encodePart}. */
	public static String decodePart(String encoded) {

		if (null == encoded || encoded.isEmpty())
			return "";

		try {
			return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException("UTF-8 is not optional", e); // unreachable
		}
	}

	/**
	 * Does this query string carry a parameter whose name is inside the reserved {@code fc-} space?
	 *
	 * Such an include is not combinable: its parameter would arrive at the origin beside the ones
	 * this protocol puts there, and the origin has no way to tell them apart.
	 */
	public static boolean hasReservedParam(String query) {

		if (null == query || query.isEmpty())
			return false;

		int scanIdx = 0;
		while (scanIdx <= query.length())
		{
			int ampIdx = query.indexOf('&', scanIdx);
			int pairEnd = (-1 == ampIdx) ? query.length() : ampIdx;

			int eqIdx = query.indexOf('=', scanIdx);
			int nameEnd = (-1 == eqIdx || eqIdx > pairEnd) ? pairEnd : eqIdx;

			if (nameEnd - scanIdx >= PARAM_PREFIX.length()
					&& query.regionMatches(true, scanIdx, PARAM_PREFIX, 0, PARAM_PREFIX.length()))
				return true;

			if (-1 == ampIdx)
				break;

			scanIdx = ampIdx + 1;
		}

		return false;
	}

	/** Everything before the {@code ?} - the endpoint a batch is grouped by and addressed to. */
	public static String endpointOf(String url) {

		if (null == url)
			return null;

		int idx = url.indexOf('?');
		return (-1 == idx) ? url : url.substring(0, idx);
	}

	/**
	 * The URI path of an absolute include URL - {@code http://host:8080/a/b.htm?x=1} to
	 * {@code /a/b.htm}.
	 *
	 * Exists so the combine path can apply {@code dynamic-urls.conf} to a member the way
	 * {@code FrontCacheEngine.ignoreCache} applies it to a request: against the path, since that is
	 * what {@code RequestContext.getRequestURI()} holds. Matching a different string here would make
	 * a combined member cacheable where a single include was not.
	 */
	public static String pathOf(String url) {

		String noQuery = endpointOf(url);
		if (null == noQuery)
			return null;

		int schemeIdx = noQuery.indexOf("//");
		if (-1 == schemeIdx)
			return noQuery; // already a path

		int slashIdx = noQuery.indexOf('/', schemeIdx + 2);
		return (-1 == slashIdx) ? "/" : noQuery.substring(slashIdx);
	}

	/** Everything after the {@code ?}, or {@code ""} when there is none. Never null. */
	public static String queryOf(String url) {

		if (null == url)
			return "";

		int idx = url.indexOf('?');
		return (-1 == idx) ? "" : url.substring(idx + 1);
	}

	/**
	 * Batch identity: the endpoint plus the {@code combine} attribute's value. The endpoint is always
	 * part of it, so a group name reused across two paths splits into two batches rather than
	 * producing a request no single endpoint can answer.
	 */
	public static String groupKey(String url, String combineGroup) {
		return endpointOf(url) + " " + combineGroup;
	}
}
