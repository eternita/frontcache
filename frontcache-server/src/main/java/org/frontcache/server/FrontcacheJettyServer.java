/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.server;

import java.io.File;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Embedded Jetty 12 (Jakarta EE10) launcher for running Frontcache standalone
 * locally. Deploys the webapp at context "/".
 *
 * System properties:
 *   frontcache.jetty.port    - listen port (default 9080)
 *   frontcache.jetty.webapp  - path to the exploded webapp directory (or war)
 */
public class FrontcacheJettyServer {

	public static void main(String[] args) throws Exception {

		int port = Integer.parseInt(System.getProperty("frontcache.jetty.port", "9080"));
		String webDir = System.getProperty("frontcache.jetty.webapp");

		System.out.println("Starting Frontcache Jetty Server (port=" + port + ", webapp=" + webDir + ") ...");

		// Honor X-Forwarded-* from the nginx front door so request.getScheme()/
		// getServerPort() reflect the client-facing values (https/443), not the
		// plain-HTTP backend hop. Without this the management-port security check
		// (front-cache.management.port) and absolute-URL/redirect generation see
		// port 80 and break behind a TLS-terminating proxy.
		Server server = new Server();
		HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.addCustomizer(new ForwardedRequestCustomizer());
		ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
		connector.setPort(port);
		server.addConnector(connector);

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath("/");
		webapp.setWar(new File(webDir).getAbsolutePath());
		webapp.setThrowUnavailableOnStartupException(true);

		// Scan container jars for ServletContainerInitializers (JSP/JSTL support).
		// The JSP/JSTL impls live in container jars, so their META-INF TLDs are only
		// found if the jars are named in the container-scan pattern.
		webapp.addConfiguration(new AnnotationConfiguration());
		webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
				".*/jakarta\\.servlet\\.jsp\\.jstl-.*\\.jar$|"
				+ ".*/org\\.glassfish\\.web\\..*\\.jar$|"
				+ ".*/jetty-ee10-apache-jsp-.*\\.jar$|"
				+ ".*/org\\.mortbay\\.jasper\\..*\\.jar$|"
				+ ".*/jetty-ee10-glassfish-jstl-.*\\.jar$|"
				+ ".*/taglibs.*\\.jar$");

		server.setHandler(webapp);
		server.start();

		System.out.println("Frontcache Jetty Server has been started successfully ...");

		server.join();
	}

}
