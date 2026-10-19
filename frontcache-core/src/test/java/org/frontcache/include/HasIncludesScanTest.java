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
package org.frontcache.include;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.junit.Test;

/**
 * {@code hasIncludes()} after it stopped decoding the page with the platform charset.
 *
 * It used to do {@code new String(content)} - decoding the whole page, and for anything non-ASCII
 * expanding it into a UTF-16 array twice the size - to answer a boolean, on every cacheable response,
 * and overwhelmingly to answer "no". It now scans the bytes through a Latin-1 view, which performs no
 * charset conversion. That is only safe if it agrees with the decoded scan on the traffic it actually
 * sees, which is what this pins: the reference implementation below is the code that was replaced,
 * and every case asserts both against it and against the expected answer.
 */
public class HasIncludesScanTest {

	/** The exact pre-change implementation, kept as the oracle. */
	private static boolean decodedScan(byte[] content) {
		String contentStr = new String(content);
		int startIdx = contentStr.indexOf("<fc:include");
		if (-1 < startIdx) {
			int endIdx = contentStr.indexOf("/>", startIdx);
			if (-1 < endIdx && (endIdx - startIdx) < IncludeProcessor.MAX_INCLUDE_LENGHT)
				return true;
		}
		return false;
	}

	/** Minimal concrete subclass - hasIncludes lives on the base and needs nothing else. */
	private static final class Probe extends IncludeProcessorBase {
		@Override
		public WebResponse processIncludes(WebResponse parent, String base, Map<String, List<String>> headers,
				FcHttpClient client, RequestContext context, int recursionLevel) {
			throw new UnsupportedOperationException();
		}
		@Override
		public void init(Properties properties) { }
	}

	private final IncludeProcessor processor = new Probe();

	private static WebResponse textResponse(String body) {
		WebResponse r = new WebResponse("http://example.com/p.html", body.getBytes(StandardCharsets.UTF_8));
		r.addHeader(FCHeaders.CONTENT_TYPE, "text/html; charset=UTF-8");
		return r;
	}

	private void assertAgrees(String label, String body, boolean expected) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		assertEquals(label + " - the oracle itself must say " + expected, expected, decodedScan(bytes));
		assertEquals(label, expected, processor.hasIncludes(textResponse(body), 0));
	}

	@Test
	public void agreesWithTheDecodedScanItReplaced() {
		assertAgrees("plain page, no marker",
				"<html><body>nothing to see</body></html>", false);

		assertAgrees("a well-formed include",
				"<html><body><fc:include url=\"/inc/footer.html\" /></body></html>", true);

		assertAgrees("include at the very start",
				"<fc:include url=\"/a\" />rest", true);

		assertAgrees("include at the very end",
				"start<fc:include url=\"/a\" />", true);

		assertAgrees("marker with no closing /> anywhere",
				"<html><fc:include url=\"/never-closed\" </html>", false);

		assertAgrees("empty body", "", false);

		// a '/>' that belongs to some other self-closing tag still counts - preserved, not fixed
		assertAgrees("marker followed by an unrelated self-closing tag",
				"<fc:include url=\"/a\"<br/>", true);
	}

	/**
	 * The case that makes a byte scan different from a char scan. UTF-8 is ASCII-transparent, so the
	 * marker's bytes cannot appear inside a multi-byte sequence and cannot be missed because of one.
	 */
	@Test
	public void multiByteCharactersDoNotHideTheMarker() {
		assertAgrees("multi-byte text before the include",
				"<p>Ünïcödé — 日本語 — Ελληνικά</p><fc:include url=\"/inc/a.html\" />", true);

		assertAgrees("multi-byte text and no include",
				"<p>Ünïcödé — 日本語 — Ελληνικά</p>", false);

		assertAgrees("multi-byte text INSIDE the include tag",
				"<fc:include url=\"/inc/caffè.html\" />", true);
	}

	/**
	 * MAX_INCLUDE_LENGHT is now a byte distance rather than a char distance. Identical for an ASCII
	 * tag, and where they differ the byte distance is the larger - so the byte scan is the more
	 * conservative of the two. Both are only a gate on running the real parse.
	 */
	@Test
	public void theDistanceLimitStillApplies() {
		StringBuilder tooFar = new StringBuilder("<fc:include ");
		for (int i = 0; i < IncludeProcessor.MAX_INCLUDE_LENGHT; i++)
			tooFar.append('x');
		tooFar.append("/>");

		assertAgrees("a '/>' further than MAX_INCLUDE_LENGHT away does not count",
				tooFar.toString(), false);

		assertAgrees("a '/>' just inside the limit does count",
				"<fc:include url=\"/a\"" + "y".repeat(100) + "/>", true);
	}

	@Test
	public void theNonContentGuardsAreUnchanged() {
		String withInclude = "<fc:include url=\"/a\" />";

		WebResponse noContent = new WebResponse("http://example.com/p.html", null);
		noContent.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		assertFalse("null content", processor.hasIncludes(noContent, 0));

		assertFalse("recursion limit reached",
				processor.hasIncludes(textResponse(withInclude), IncludeProcessor.MAX_RECURSION_LEVEL));
		assertTrue("one below the recursion limit still scans",
				processor.hasIncludes(textResponse(withInclude), IncludeProcessor.MAX_RECURSION_LEVEL - 1));

		WebResponse binary = new WebResponse("http://example.com/p.png", withInclude.getBytes(StandardCharsets.UTF_8));
		binary.addHeader(FCHeaders.CONTENT_TYPE, "image/png");
		assertFalse("includes are resolved for text only", processor.hasIncludes(binary, 0));
	}

	/** A realistic page: the answer must be the same as the decoded scan's, marker or not. */
	@Test
	public void agreesOnALargePage() {
		StringBuilder page = new StringBuilder();
		page.append("<html><head><title>Ünïcödé</title></head><body>");
		for (int i = 0; i < 5000; i++)
			page.append("<div class=\"row-").append(i).append("\">cell ").append(i).append(" — ok</div>");

		assertAgrees("large page, no include", page.toString(), false);

		List<String> positions = new ArrayList<String>(Arrays.asList(
				"<fc:include url=\"/inc/x.html\" />" + page,   // at the head
				page + "<fc:include url=\"/inc/x.html\" />"));  // at the tail
		for (String body : positions)
			assertAgrees("large page with an include", body, true);
	}
}
