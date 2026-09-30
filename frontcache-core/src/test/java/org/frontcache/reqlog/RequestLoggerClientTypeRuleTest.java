/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.reqlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.FCHeaders;
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
 * The client-type column says {@code bot}; this column says which conf/bots.conf line decided that.
 *
 * Two properties are pinned here because both are silent when broken. <b>The rule sits immediately
 * after the client type</b>, which is the one column in this format ever to have been inserted
 * rather than appended - {@code bot "bingbot"} is one fact and has to read as one - so the
 * user-agent column moved right by one and these numbers are the contract that says by how much.
 * And <b>the field is quoted</b>: a bare-keyword line's rule name is the keyword itself, the shipped
 * file contains {@code Yahoo! Slurp}, and an unquoted field would change the column count on exactly
 * the lines an operator is reading when a crawler misbehaves.
 *
 * The same position holds in frontcache-failed-requests.log, across both of that file's line shapes,
 * whose failure reason (and, for a guard action, the HTTP status) still trail the base line.
 */
public class RequestLoggerClientTypeRuleTest {

	// 1-based column numbers of the base line, as the format javadoc lists them
	private static final int CLIENT_TYPE = 14;
	private static final int CLIENT_TYPE_RULE = 15;
	private static final int USER_AGENT = 16;
	private static final int BASE_LINE_COLUMNS = 16;

	private static final String BINGBOT =
			"Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)";

	private Logger requestLog;
	private Logger failedRequestLog;
	private ListAppender<ILoggingEvent> requestAppender;
	private ListAppender<ILoggingEvent> failedAppender;
	private Level previousRequestLevel;
	private Level previousFailedLevel;

	@Before
	public void captureLogs() {
		requestLog = (Logger) LoggerFactory.getLogger(RequestLogger.class);
		failedRequestLog = (Logger) LoggerFactory.getLogger("frontcache.failed-requests");

		previousRequestLevel = requestLog.getLevel();
		previousFailedLevel = failedRequestLog.getLevel();

		requestAppender = new ListAppender<ILoggingEvent>();
		requestAppender.start();
		failedAppender = new ListAppender<ILoggingEvent>();
		failedAppender.start();

		requestLog.setLevel(Level.TRACE);
		requestLog.addAppender(requestAppender);
		failedRequestLog.setLevel(Level.TRACE);
		failedRequestLog.addAppender(failedAppender);
	}

	@After
	public void releaseLogs() {
		requestLog.detachAppender(requestAppender);
		requestLog.setLevel(previousRequestLevel);
		failedRequestLog.detachAppender(failedAppender);
		failedRequestLog.setLevel(previousFailedLevel);
	}

	@Test
	public void theDecidingRuleFollowsTheClientTypeItExplains() {

		RequestLogger.logRequest("https://www.example-site.com/index.htm", true, true, 1, 50874,
				context("bot", "bingbot", BINGBOT));

		List<String> columns = onlyLine(requestAppender);

		assertEquals("the base line gained exactly one column: " + columns, BASE_LINE_COLUMNS, columns.size());
		assertEquals("bot", columns.get(CLIENT_TYPE - 1));
		assertEquals("bingbot", columns.get(CLIENT_TYPE_RULE - 1));
		assertEquals("the user-agent column moved right by one, and no further", BINGBOT,
				columns.get(USER_AGENT - 1));
	}

	/**
	 * {@code Yahoo! Slurp} is a bare keyword in the shipped conf/bots.conf, so it is also a rule
	 * name - with a space in it. Unquoted it would split into two columns and push the field count
	 * of that one line past every other line in the file.
	 */
	@Test
	public void aRuleNameWithASpaceKeepsTheColumnCount() {

		RequestLogger.logRequest("https://www.example-site.com/index.htm", true, true, 1, 50874,
				context("bot", "Yahoo! Slurp", "Mozilla/5.0 (compatible; Yahoo! Slurp)"));

		List<String> columns = onlyLine(requestAppender);

		assertEquals("a space in the rule name must not add a column: " + columns,
				BASE_LINE_COLUMNS, columns.size());
		assertEquals("Yahoo! Slurp", columns.get(CLIENT_TYPE_RULE - 1));
	}

	/**
	 * Every served request is classified in FrontCacheEngine.init(), so this is the hand-built
	 * context of a test or a caller outside the engine - a log field is best-effort and must never
	 * be the reason a line is malformed.
	 */
	@Test
	public void anUnstampedContextReadsAsADash() {

		RequestLogger.logRequest("https://www.example-site.com/index.htm", true, false, 1, 2,
				context("guest", null, "curl/8.4.0"));

		List<String> columns = onlyLine(requestAppender);

		assertEquals(BASE_LINE_COLUMNS, columns.size());
		assertEquals("-", columns.get(CLIENT_TYPE_RULE - 1));
	}

	/** An include's copied context carries the page's verdict - the whole point of copying it. */
	@Test
	public void anIncludeCopyCarriesTheRule() {

		RequestContext copy = new RequestContext();
		copy.putAll(context("bot", "bingbot", BINGBOT));

		RequestLogger.logRequest("https://www.example-site.com/fc/include-footer.htm", true, true, 0, 1581, copy);

		assertEquals("bingbot", onlyLine(requestAppender).get(CLIENT_TYPE_RULE - 1));
	}

	/**
	 * The fallback shape of frontcache-failed-requests.log: the same base line, with the failure
	 * category appended AFTER the rule.
	 */
	@Test
	public void aFallbackLineTrailsTheRuleWithTheReason() {

		RequestContext context = context("guest", "default", "curl/8.4.0");
		context.setFallbackUsed("timeout");

		RequestLogger.logRequest("https://www.example-site.com/index.htm", true, false, 1000, -1, context);

		List<String> columns = onlyLine(failedAppender);

		assertEquals(BASE_LINE_COLUMNS + 1, columns.size());
		assertEquals("default", columns.get(CLIENT_TYPE_RULE - 1));
		assertEquals("timeout", columns.get(BASE_LINE_COLUMNS));
	}

	/**
	 * The guard shape of the same file. Two rule names now share a line - the bots.conf one that
	 * decided the client type, then the guard rule that acted on the request - and the HTTP status
	 * stays last, as {@link RequestLogger#logGuardedRequest} documents.
	 */
	@Test
	public void aGuardedLineNamesBothRulesAndKeepsTheStatusLast() {

		RequestLogger.logGuardedRequest("https://www.example-site.com/login.htm", "bad-request", 400, "rejected",
				context("bot", "bingbot", BINGBOT));

		List<String> columns = onlyLine(failedAppender);

		assertEquals(BASE_LINE_COLUMNS + 2, columns.size());
		assertEquals("bingbot", columns.get(CLIENT_TYPE_RULE - 1));
		assertEquals("bad-request", columns.get(BASE_LINE_COLUMNS));
		assertEquals("400", columns.get(BASE_LINE_COLUMNS + 1));
	}

	/**
	 * front-cache.log-to-headers - the one place the answer arrives without shell access to the
	 * node, which is how "why was I served the bot TTL?" gets answered from a curl.
	 */
	@Test
	public void theTraceHeaderCarriesTheRule() {

		RequestContext context = context("bot", "bingbot", BINGBOT);
		context.setLogToHTTPHeaders();

		RequestLogger.logRequestToHeader("https://www.example-site.com/index.htm", "toplevel", true, false, 1, 50874,
				context, "0");

		String header = context.getTraceHeaders().get(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");
		assertTrue("nothing was buffered for the trace header", null != header);

		List<String> columns = split(header);
		assertEquals("bingbot", columns.get(columns.size() - 1));
		assertEquals("bot", columns.get(columns.size() - 2));
	}

	private static RequestContext context(String clientType, String clientTypeRule, String userAgent) {

		RequestContext context = new RequestContext();
		context.setRequestId("1649b11f-8acf-4718-8e0b-abdcf7356212");
		context.setDomain("www.example-site.com");
		context.setRequestType("toplevel");
		context.setClientIP("203.0.113.7");
		context.setFrontCacheId("front-cache-local-1");
		context.setClientType(clientType);
		context.setClientTypeRule(clientTypeRule);
		context.setRequest(request(userAgent));

		return context;
	}

	/** Answers the User-Agent and a method, and null for every other header - all these lines read. */
	private static HttpServletRequest request(final String userAgent) {

		return (HttpServletRequest) Proxy.newProxyInstance(
				RequestLoggerClientTypeRuleTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {

						if ("getMethod".equals(method.getName()))
							return "GET";

						if ("getHeader".equals(method.getName()) && "User-Agent".equals(args[0]))
							return userAgent;

						return null;
					}
				});
	}

	private static List<String> onlyLine(ListAppender<ILoggingEvent> appender) {

		assertEquals("expected exactly one line, got " + appender.list.size(), 1, appender.list.size());

		return split(appender.list.get(0).getFormattedMessage());
	}

	/**
	 * Splits a log line into its columns the way a consumer has to: on spaces OUTSIDE the quoted
	 * fields, with the quotes stripped. A column count asserted any other way would not notice the
	 * thing these tests exist to catch.
	 */
	private static List<String> split(String line) {

		List<String> columns = new ArrayList<String>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;

		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);

			if ('"' == c)
				quoted = !quoted;
			else if (' ' == c && !quoted)
			{
				columns.add(current.toString());
				current.setLength(0);
			}
			else
				current.append(c);
		}
		columns.add(current.toString());

		return columns;
	}

}
