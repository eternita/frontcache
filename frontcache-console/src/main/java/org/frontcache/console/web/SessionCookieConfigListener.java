/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web;

import org.frontcache.console.service.FrontcacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;

/**
 * The two session-cookie attributes web.xml cannot express - see docs/console-login-ui.md
 * section 4.4.
 *
 * {@code HttpOnly}, the cookie name and the timeout are in web.xml. These two are not:
 *
 * <ul>
 * <li><b>{@code SameSite=Lax}</b> has no web.xml element; {@code SessionCookieConfig.setAttribute}
 *     (Servlet 6.0) is the only way to set it. It is a real gain over Basic, which section 8.1 of
 *     docs/basic-auth-proposal.md had to note was immune to SameSite because Basic credentials are
 *     not cookies. {@code Lax} rather than {@code Strict} so following a bookmark or a link into
 *     the console does not land on the login page while a perfectly good session exists.</li>
 * <li><b>{@code Secure}</b> is in web.xml but has to be a decision, not a constant - see
 *     {@link FrontcacheService#isSessionCookieSecure()}.</li>
 * </ul>
 *
 * <b>This must run as a context listener and cannot be moved into a filter's {@code init()}.</b>
 * {@code SessionCookieConfig}'s setters throw {@code IllegalStateException} once the context is
 * initialized, and {@code contextInitialized} is the documented window. It is declared AFTER
 * {@code ContextLoaderListener} in web.xml, because listeners are notified in declaration order
 * and the Spring root context has to exist for {@link FrontcacheService} to be readable.
 */
public class SessionCookieConfigListener implements ServletContextListener {

	private static final Logger logger = LoggerFactory.getLogger(SessionCookieConfigListener.class);

	@Override
	public void contextInitialized(ServletContextEvent event) {

		WebApplicationContext context =
				WebApplicationContextUtils.getRequiredWebApplicationContext(event.getServletContext());

		FrontcacheService frontcacheService = context.getBean(FrontcacheService.class);

		SessionCookieConfig cookieConfig = event.getServletContext().getSessionCookieConfig();

		try {
			cookieConfig.setAttribute("SameSite", "Lax");
			cookieConfig.setSecure(frontcacheService.isSessionCookieSecure());
		} catch (IllegalStateException e) {
			// A container that considers the context already initialized here. The console still
			// works - it loses one cookie attribute - so say so rather than refusing to start.
			logger.warn("Could not set the session cookie attributes ({}). The session cookie will"
					+ " carry the container's defaults.", e.getMessage());
			return;
		}

		if (!frontcacheService.isSessionCookieSecure())
			logger.info(" -- Session cookie: SameSite=Lax, HttpOnly. Set session-cookie-secure = true"
					+ " in frontcache-console.conf when the console is reached over HTTPS.");
	}

}
