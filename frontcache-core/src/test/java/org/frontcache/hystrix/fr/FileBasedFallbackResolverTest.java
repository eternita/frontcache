/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix.fr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;

import org.frontcache.FCConfig;
import org.frontcache.core.DomainContext;
import org.frontcache.core.WebResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit test for {@link FileBasedFallbackResolver} focused on the regex -> file mapping.
 *
 * Unlike {@code FallbackHystrixTests} (which drives the resolver through the full e2e server),
 * this test exercises the resolver in isolation: it points {@code frontcache.home} at a tiny
 * fixture directory ({@code src/test/resources/fallback-home}) whose {@code conf/fallbacks.conf}
 * holds HOST-AGNOSTIC patterns for real example.com URLs, then asserts each URL resolves to the
 * body of its mapped file.
 *
 * Things worth remembering that this test pins down:
 *  - the resolver matches with {@code Pattern.matcher(url).matches()} (FULL-string match), so a
 *    pattern must span scheme -> host -> path -> query;
 *  - {@code https?://[^/]+} makes a rule host/scheme-independent (same rule serves www.example.com
 *    and localhost:9080);
 *  - entries are matched in insertion order, first match wins;
 *  - a URL matching nothing (or a file that can't be read) yields the default fallback;
 *  - fallbacks are stamped as non-cacheable 503s.
 */
public class FileBasedFallbackResolverTest {

	// No `front-cache.domains` in the fixture -> every fallback lives under DEFAULT_DOMAIN, and
	// getFallback() falls back to that map for any DomainContext whose domain isn't configured.
	private static final DomainContext DOMAIN =
			new DomainContext("example.com", "test-site-key-fallback", "origin.example.com", "80", "443");

	private static FileBasedFallbackResolver resolver;

	@BeforeClass
	public static void setUpClass() throws Exception {
		// Resolve the fixture FRONTCACHE_HOME from the test classpath and point frontcache.home at
		// it BEFORE the resolver (and thus FCConfig) is first touched. FileBasedFallbackResolver /
		// FCConfig read this system property live when loading configs and reading fallback files.
		URL confMarker = FileBasedFallbackResolverTest.class.getClassLoader()
				.getResource("fallback-home/conf/fallbacks.conf");
		assertNotNull("fixture fallback-home/conf/fallbacks.conf not found on test classpath", confMarker);
		File fallbackHome = new File(confMarker.toURI()).getParentFile().getParentFile();
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, fallbackHome.getAbsolutePath());

		// default ctor loads fallbacks.conf; skip init() so we don't fetch anything from an origin -
		// the fixture files already exist on disk.
		resolver = new FileBasedFallbackResolver();
	}

	/** Resolve a fallback and return its body as a trimmed UTF-8 string. */
	private String fallbackBody(String url) throws Exception {
		WebResponse response = resolver.getFallback(DOMAIN, "test", url);
		assertNotNull("resolver returned null for " + url, response);
		return new String(response.getContent(), "UTF-8").trim();
	}

	private void assertFallback(String url, String expectedMarker) throws Exception {
		assertEquals("wrong fallback file served for " + url, expectedMarker, fallbackBody(url));
	}

	// --- one assertion per configured URL (host-agnostic patterns, real example.com URLs) ---

	@Test
	public void includeHeaderExactQuery() throws Exception {
		// also exercises the "Content-Type:...\n" header-stripping branch in getFallbackFromFile
		assertFallback("https://www.example.com/fc/include-header.htm?view=desktop&locale=en",
				"FALLBACK:include-header-en");
	}

	@Test
	public void welcome() throws Exception {
		assertFallback("https://www.example.com/en/welcome.htm", "FALLBACK:welcome");
	}

	@Test
	public void search() throws Exception {
		assertFallback("https://www.example.com/search.htm", "FALLBACK:search");
	}

	@Test
	public void coinsByDates() throws Exception {
		assertFallback("https://www.example.com/coins_by_dates.htm", "FALLBACK:coins_by_dates");
	}

	@Test
	public void crawledCoinsPrices() throws Exception {
		assertFallback("https://www.example.com/en/crawled-coins-prices.htm", "FALLBACK:crawled-coins-prices");
	}

	@Test
	public void coinVisitCounter() throws Exception {
		assertFallback("https://www.example.com/coin-visit-counter.htm", "FALLBACK:coin-visit-counter");
	}

	@Test
	public void recentUpdatesAdd() throws Exception {
		assertFallback("https://www.example.com/fc/recent-updates-add.htm?locale=en", "FALLBACK:recent-updates-add");
	}

	@Test
	public void includeFooter() throws Exception {
		assertFallback("https://www.example.com/fc/include-footer.htm?locale=en", "FALLBACK:include-footer");
	}

	// --- behaviour of the patterns themselves ---

	/** Same paths under a different scheme/host:port must resolve to the same files. */
	@Test
	public void patternsAreHostAndSchemeAgnostic() throws Exception {
		assertFallback("http://localhost:9080/search.htm", "FALLBACK:search");
		assertFallback("http://staging.internal:8080/coin-visit-counter.htm", "FALLBACK:coin-visit-counter");
		assertFallback("https://another-host.example/fc/include-footer.htm?locale=es", "FALLBACK:include-footer");
	}

	/** The `.htm.*` tail tolerates arbitrary query strings / trailing params. */
	@Test
	public void trailingQueryStringStillMatches() throws Exception {
		assertFallback("https://www.example.com/search.htm?q=coins&page=2", "FALLBACK:search");
	}

	/** The 2-letter-locale segment generalizes across locales, not just /en/. */
	@Test
	public void localeSegmentIsGeneralized() throws Exception {
		assertFallback("https://www.example.com/de/welcome.htm", "FALLBACK:welcome");
		assertFallback("https://www.example.com/fr/crawled-coins-prices.htm", "FALLBACK:crawled-coins-prices");
	}

	/** A URL matching no pattern gets the default fallback, not someone else's file. */
	@Test
	public void unmatchedUrlYieldsDefaultFallback() throws Exception {
		String body = fallbackBody("https://www.example.com/no-such-page.htm");
		assertTrue("expected the default fallback for an unmatched URL, got: " + body,
				body.contains("Default Fallback"));
	}

	/** include-header's EXACT-query rule must not swallow a bare include-header.htm request. */
	@Test
	public void includeHeaderWithoutExpectedQueryFallsThrough() throws Exception {
		String body = fallbackBody("https://www.example.com/fc/include-header.htm");
		assertTrue("bare include-header.htm should hit the default fallback, got: " + body,
				body.contains("Default Fallback"));
	}

	/** Fallbacks must be non-cacheable 503s so downstream caches/CDNs don't pin them. */
	@Test
	public void fallbackIsNonCacheable503() throws Exception {
		WebResponse response = resolver.getFallback(DOMAIN, "test", "https://www.example.com/search.htm");
		assertEquals(FileBasedFallbackResolver.FALLBACK_HTTP_STATUS, response.getStatusCode());
		assertTrue(response.getHeader("Cache-Control").contains("no-store"));
	}
}
