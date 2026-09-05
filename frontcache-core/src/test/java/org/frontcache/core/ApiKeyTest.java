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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link ApiKey} - the management API's only access control, now that the
 * {@code front-cache.management.port} check, which never was one, is gone.
 *
 * The rules worth pinning are the ones that decide whether a deployment keeps working: an unconfigured
 * node stays open (refusing on upgrade would take away an operator's dashboards to protect a node that
 * never asked), {@code Authorization: Bearer} is the form everything sends now, and the pre-2.7 header
 * is still ACCEPTED - because an embedded agent, a separately deployed console or an older peer node
 * can be older than the node it calls, and none of them upgrade on our schedule.
 */
public class ApiKeyTest {

	/** Restored rather than blanked: FCConfig is process-wide, and other tests in this fork have one. */
	private final String original = FCConfig.getProperty(ApiKey.API_KEY_PROPERTY, "");

	@After
	public void tearDown()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, original);
	}

	private static HttpServletRequest with(String header, String value)
	{
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(header)).thenReturn(value);
		return request;
	}

	private static HttpServletRequest bare()
	{
		return mock(HttpServletRequest.class);
	}

	// ---- unconfigured: open, exactly as before -------------------------------------------------

	@Test
	public void aNodeWithNoApiKeyAuthorizesEveryone()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "");

		assertFalse(ApiKey.isConfigured());
		assertTrue("an unconfigured node must not start refusing traffic on upgrade",
				ApiKey.isAuthorized(bare()));
	}

	@Test
	public void whitespaceIsNotAApiKey()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "   ");

		assertFalse("a blank value is not a secret somebody meant to set", ApiKey.isConfigured());
		assertTrue(ApiKey.isAuthorized(bare()));
	}

	// ---- configured: the secret is required ----------------------------------------------------

	@Test
	public void aConfiguredNodeRefusesARequestWithoutTheKey()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertTrue(ApiKey.isConfigured());
		assertFalse(ApiKey.isAuthorized(bare()));
	}

	/**
	 * The compatibility promise, and the reason this cannot simply be deleted: frontcache-agent is a
	 * published artifact embedded in customer applications, and a node cannot choose when those
	 * upgrade. Deleting this test is the deliberate act that ends the grace period.
	 */
	@Test
	@SuppressWarnings("deprecation")
	public void theDeprecatedHeaderIsStillAccepted()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertTrue("a pre-2.7 agent must keep working against a 2.7-or-later node",
				ApiKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "s3cret")));
		assertFalse(ApiKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "wrong")));
		assertFalse(ApiKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "")));
	}

	/** Bearer wins when a caller mid-migration sends both, so the current form is the one that counts. */
	@Test
	@SuppressWarnings("deprecation")
	public void bearerTakesPrecedenceOverTheDeprecatedHeader()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		HttpServletRequest both = mock(HttpServletRequest.class);
		when(both.getHeader("Authorization")).thenReturn("Bearer s3cret");
		when(both.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY)).thenReturn("stale");

		assertTrue(ApiKey.isAuthorized(both));
	}

	/** The one form everything sends now - and the only one Prometheus can send at all. */
	@Test
	public void authorizationBearerCarriesIt()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertTrue(ApiKey.isAuthorized(with("Authorization", "Bearer s3cret")));
		assertFalse(ApiKey.isAuthorized(with("Authorization", "Bearer wrong")));
	}

	/** RFC 7235 makes the scheme token case-insensitive, and clients differ on how they spell it. */
	@Test
	public void theBearerSchemeIsCaseInsensitive()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertTrue(ApiKey.isAuthorized(with("Authorization", "bearer s3cret")));
		assertTrue(ApiKey.isAuthorized(with("Authorization", "BEARER s3cret")));
	}

	@Test
	public void anotherAuthorizationSchemeIsNotMistakenForTheKey()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertFalse(ApiKey.isAuthorized(with("Authorization", "Basic czNjcmV0")));
		assertFalse("the scheme is not the credential", ApiKey.isAuthorized(with("Authorization", "s3cret")));
	}

	/** A prefix of the key is not the key - the comparison is constant-time, not lenient. */
	@Test
	public void aPartialKeyIsRefused()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertFalse(ApiKey.isAuthorized(with("Authorization", "Bearer s3cr")));
		assertFalse(ApiKey.isAuthorized(with("Authorization", "Bearer s3cretIsLonger")));
	}

	/** Read per request, so reloading a node's configuration changes the key actually enforced. */
	@Test
	public void theKeyIsReReadRatherThanCached()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "first");
		assertTrue(ApiKey.isAuthorized(with("Authorization", "Bearer first")));

		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "second");

		assertFalse("the old key must stop working", ApiKey.isAuthorized(with("Authorization", "Bearer first")));
		assertTrue(ApiKey.isAuthorized(with("Authorization", "Bearer second")));
	}

	@Test
	public void aNullRequestIsNotAuthorized()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");

		assertFalse(ApiKey.isAuthorized(null));
	}

	// ---- the deprecation warning ---------------------------------------------------------------

	/**
	 * Once, not per request. A fleet mid-upgrade has every management call arriving on the old header,
	 * and a warning per call would bury the log it is trying to be visible in.
	 *
	 * Worth a test rather than an eyeball because it is invisible when broken - and because the shipped
	 * fc-logback.xml routes WARN to stdout only (its file appenders use exact-match level filters), so
	 * it does not show up where someone would go looking.
	 */
	@Test
	@SuppressWarnings("deprecation")
	public void theLegacyHeaderIsWarnedAboutExactlyOnce()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");
		ApiKey.legacyHeaderLogged.set(false);   // another test in this fork may have tripped it

		Logger logger = (Logger) LoggerFactory.getLogger(ApiKey.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<ILoggingEvent>();
		captured.start();
		logger.addAppender(captured);

		try {
			for (int i = 0; i < 5; i++)
				assertTrue(ApiKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "s3cret")));

			List<ILoggingEvent> warnings = captured.list;
			assertEquals("five legacy calls, one warning", 1, warnings.size());
			assertEquals(Level.WARN, warnings.get(0).getLevel());
			assertTrue("the warning must name the header being deprecated: " + warnings.get(0).getFormattedMessage(),
					warnings.get(0).getFormattedMessage().contains(FCHeaders.X_FRONTCACHE_SITE_KEY));
			assertTrue("and the form to use instead",
					warnings.get(0).getFormattedMessage().contains("Bearer"));

		} finally {
			logger.detachAppender(captured);
		}
	}

	/** Bearer is the current form, so it must not warn about anything. */
	@Test
	public void theCurrentFormWarnsAboutNothing()
	{
		FCConfig.setProperty(ApiKey.API_KEY_PROPERTY, "s3cret");
		ApiKey.legacyHeaderLogged.set(false);

		Logger logger = (Logger) LoggerFactory.getLogger(ApiKey.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<ILoggingEvent>();
		captured.start();
		logger.addAppender(captured);

		try {
			assertTrue(ApiKey.isAuthorized(with("Authorization", "Bearer s3cret")));
			assertTrue("Bearer is not deprecated", captured.list.isEmpty());

		} finally {
			logger.detachAppender(captured);
		}
	}

}
