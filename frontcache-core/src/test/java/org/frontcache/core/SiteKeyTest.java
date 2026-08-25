/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link SiteKey} - the management API's actual access control, now that {@link ManagementPort} says
 * plainly that it is not one.
 *
 * The rules worth pinning are the two that decide whether a deployment keeps working: an unconfigured
 * node stays open (refusing on upgrade would take away an operator's dashboards to protect a node that
 * never asked), and a configured node accepts the secret by either route.
 */
public class SiteKeyTest {

	/** Restored rather than blanked: FCConfig is process-wide, and other tests in this fork have one. */
	private final String original = FCConfig.getProperty(SiteKey.SITE_KEY_PROPERTY, "");

	@After
	public void tearDown()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, original);
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
	public void aNodeWithNoSiteKeyAuthorizesEveryone()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "");

		assertFalse(SiteKey.isConfigured());
		assertTrue("an unconfigured node must not start refusing traffic on upgrade",
				SiteKey.isAuthorized(bare()));
	}

	@Test
	public void whitespaceIsNotASiteKey()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "   ");

		assertFalse("a blank value is not a secret somebody meant to set", SiteKey.isConfigured());
		assertTrue(SiteKey.isAuthorized(bare()));
	}

	// ---- configured: the secret is required ----------------------------------------------------

	@Test
	public void aConfiguredNodeRefusesARequestWithoutTheKey()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertTrue(SiteKey.isConfigured());
		assertFalse(SiteKey.isAuthorized(bare()));
	}

	@Test
	public void theFrontcacheHeaderCarriesIt()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertTrue(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "s3cret")));
		assertFalse(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "wrong")));
		assertFalse(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "")));
	}

	/** The form Prometheus can actually send: scrape_config has no arbitrary-header option. */
	@Test
	public void authorizationBearerCarriesIt()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertTrue(SiteKey.isAuthorized(with("Authorization", "Bearer s3cret")));
		assertFalse(SiteKey.isAuthorized(with("Authorization", "Bearer wrong")));
	}

	/** RFC 7235 makes the scheme token case-insensitive, and clients differ on how they spell it. */
	@Test
	public void theBearerSchemeIsCaseInsensitive()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertTrue(SiteKey.isAuthorized(with("Authorization", "bearer s3cret")));
		assertTrue(SiteKey.isAuthorized(with("Authorization", "BEARER s3cret")));
	}

	@Test
	public void anotherAuthorizationSchemeIsNotMistakenForTheKey()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertFalse(SiteKey.isAuthorized(with("Authorization", "Basic czNjcmV0")));
		assertFalse("the scheme is not the credential", SiteKey.isAuthorized(with("Authorization", "s3cret")));
	}

	/** A prefix of the key is not the key - the comparison is constant-time, not lenient. */
	@Test
	public void aPartialKeyIsRefused()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertFalse(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "s3cr")));
		assertFalse(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "s3cretIsLonger")));
	}

	/** Read per request, so reloading a node's configuration changes the key actually enforced. */
	@Test
	public void theKeyIsReReadRatherThanCached()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "first");
		assertTrue(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "first")));

		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "second");

		assertFalse("the old key must stop working", SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "first")));
		assertTrue(SiteKey.isAuthorized(with(FCHeaders.X_FRONTCACHE_SITE_KEY, "second")));
	}

	@Test
	public void aNullRequestIsNotAuthorized()
	{
		FCConfig.setProperty(SiteKey.SITE_KEY_PROPERTY, "s3cret");

		assertFalse(SiteKey.isAuthorized(null));
	}

}
