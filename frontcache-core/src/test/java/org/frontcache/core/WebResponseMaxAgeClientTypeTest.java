/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.frontcache.cache.CacheProcessor;
import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The {@code maxAge="[bot|guest:]30d"} client-type split.
 *
 * The split is the whole point of the prefix: a page worth caching for a crawler is often exactly
 * the page you must not serve a signed-in visitor from cache. A prefix therefore has to mean "this
 * long for that client type, and NOT cacheable for the other one".
 *
 * <h2>The defect this pins</h2>
 *
 * The {@code guest:} branch of {@code setExpireTime} tested {@code REQUEST_CLIENT_TYPE_BOT} - the
 * same constant as the branch above it - so it could never be taken. A {@code guest:} prefix fell
 * through to the no-prefix default and cached for BOTH client types: the exact opposite of what it
 * asked for, and the failure mode the feature exists to prevent. Nothing caught it because every
 * fixture in the repo, unit and e2e, uses {@code bot:}; {@code guestPrefixIsNotCacheableForBots} is
 * the case that had no coverage at all.
 */
public class WebResponseMaxAgeClientTypeTest {

	private ListAppender<ILoggingEvent> appender;

	private Logger webResponseLogger;

	@After
	public void tearDown() {
		if (null != appender && null != webResponseLogger)
			webResponseLogger.detachAppender(appender);
	}

	private static Map<String, Long> expireTimes(String maxAge) {
		return new WebResponse("http://example.com/p.html", "body".getBytes(), maxAge, null)
				.getExpireTimeMap();
	}

	/** A future timestamp, i.e. "cacheable, expiring then" - the parser turns 30d into one. */
	private static void assertCacheable(String what, Long expireTime) {
		assertTrue(what + " must be cacheable, was " + expireTime,
				CacheProcessor.CACHE_FOREVER == expireTime || expireTime > System.currentTimeMillis());
	}

	/** The branch that always worked. Kept so a fix to the other one cannot quietly break this. */
	@Test
	public void botPrefixCachesForBotsOnly() {
		Map<String, Long> expireTimes = expireTimes("bot:30d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("a bot: prefix must leave guests uncached",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/**
	 * The defect, from the guest side: this returned a cacheable timestamp for BOTH types, so a
	 * fragment marked guest-only was also served to crawlers from cache.
	 */
	@Test
	public void guestPrefixCachesForGuestsOnly() {
		Map<String, Long> expireTimes = expireTimes("guest:30d");

		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
		assertEquals("a guest: prefix must leave bots uncached",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
	}

	/**
	 * Stated separately because it is the half the bug inverted, and the half that matters: the
	 * point of guest:30d is that a crawler must NOT be served this from cache.
	 */
	@Test
	public void guestPrefixIsNotCacheableForBots() {
		Map<String, Long> expireTimes = expireTimes("guest:30d");

		assertTrue("guest:30d must be dynamic for bots",
				!FCUtils.isWebComponentCacheableForClientType(expireTimes, FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertTrue("guest:30d must be cacheable for guests",
				FCUtils.isWebComponentCacheableForClientType(expireTimes, FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** No prefix is the common case: one TTL, both client types, no warning. */
	@Test
	public void noPrefixCachesForBoth() {
		Map<String, Long> expireTimes = expireTimes("30d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/**
	 * An unrecognized prefix still caches for both - changing that would take pages out of cache on
	 * upgrade - but it no longer does so silently. This is the shape of mistake that hid the defect,
	 * and after the browser -> guest rename it is what an un-updated page will produce.
	 */
	@Test
	public void anUnknownPrefixWarnsAndNamesTheValidOnes() {
		webResponseLogger = (Logger) LoggerFactory.getLogger(WebResponse.class);
		appender = new ListAppender<ILoggingEvent>();
		appender.start();
		webResponseLogger.addAppender(appender);

		Map<String, Long> expireTimes = expireTimes("browser:30d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));

		List<ILoggingEvent> warnings = appender.list.stream()
				.filter(event -> Level.WARN == event.getLevel())
				.toList();

		assertEquals("one warning expected, got " + warnings, 1, warnings.size());
		String message = warnings.get(0).getFormattedMessage();
		assertTrue(message, message.contains("browser"));
		assertTrue(message, message.contains(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}
}
