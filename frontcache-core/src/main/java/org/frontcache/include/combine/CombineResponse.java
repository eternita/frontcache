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
package org.frontcache.include.combine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.core.FCHeaders;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The origin's half of the combine contract: build and write the response envelope.
 * See docs/archive/combine-reduce-proposal.md section 6.
 *
 * Parts must be added in the order the members arrived - {@code parts[i]} answers
 * {@code fc-part-i}. {@link #writeTo} is what sets the {@code x-frontcache-combine} header, which
 * is the only signal the edge has that the origin understood the request at all; an origin that
 * writes the JSON by hand and forgets that header will be treated as not implementing combining.
 */
public class CombineResponse {

	private final CombineEnvelope envelope = new CombineEnvelope();

	/** A part with no echo, no explicit status and no headers - i.e. an uncacheable 200 fragment. */
	public CombineResponse add(String body) {
		return add(null, 200, null, body);
	}

	/** A part with cache directives. Keys are the {@code x-frontcache-component-*} names in FCHeaders. */
	public CombineResponse add(String q, String body, Map<String, List<String>> headers) {
		return add(q, 200, headers, body);
	}

	public CombineResponse add(String q, int status, Map<String, List<String>> headers, String body) {
		envelope.add(new CombinePart(q, status, headers, body));
		return this;
	}

	/**
	 * A cacheable part, spelled the way the {@code <fc:component>} tag spells it.
	 *
	 * @param q      echo of this member's query string, or null to omit it
	 * @param body   the fragment
	 * @param maxAge {@code x-frontcache-component-maxage}, e.g. {@code "10d"}; null means not cacheable
	 * @param tags   {@code x-frontcache-component-tags}, e.g. {@code "coin|coin-42"}; null to omit
	 */
	public CombineResponse addComponent(String q, String body, String maxAge, String tags) {

		Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
		headers.put(FCHeaders.CONTENT_TYPE, Arrays.asList("text/html;charset=UTF-8"));

		if (null != maxAge)
			headers.put(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, Arrays.asList(maxAge));

		if (null != tags)
			headers.put(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, Arrays.asList(tags));

		return add(q, 200, headers, body);
	}

	/** A member the origin could not render. Not cached by the edge; that member falls back. */
	public CombineResponse addError(String q, int status) {
		return add(q, status, null, null);
	}

	public int size() {
		return envelope.getParts().size();
	}

	public CombineEnvelope getEnvelope() {
		return envelope;
	}

	public String toJson() throws IOException {
		return envelope.toJson();
	}

	/**
	 * Writes the envelope: content type, the {@code x-frontcache-combine} support signal, an explicit
	 * content length, and the JSON body.
	 *
	 * The length is set explicitly because the body is already fully in hand - letting the container
	 * fall back to chunked encoding for a response whose size is known costs the edge a
	 * read-until-EOF where it could have read a known number of bytes.
	 */
	public void writeTo(HttpServletResponse response) throws IOException {

		byte[] json = toJson().getBytes(StandardCharsets.UTF_8);

		response.setContentType(CombineProtocol.ENVELOPE_CONTENT_TYPE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader(CombineProtocol.HEADER_COMBINE, Integer.toString(CombineProtocol.VERSION));
		response.setContentLength(json.length);
		response.getOutputStream().write(json);
	}
}
