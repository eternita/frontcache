/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console;

import java.io.File;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;

/**
 * Embedded Jetty 12 (Jakarta EE10) launcher for running the Frontcache console
 * locally (replaces the former gretty jettyRun). Deploys the webapp at context "/".
 * Kept self-contained in this module so the console does not depend on the
 * frontcache-server project.
 *
 * System properties:
 *   frontcache.jetty.port    - listen port (default 7080)
 *   frontcache.jetty.webapp  - path to the exploded webapp directory (or war)
 */
public class ConsoleJettyServer {

	public static void main(String[] args) throws Exception {

		int port = Integer.parseInt(System.getProperty("frontcache.jetty.port", "7080"));
		String webDir = System.getProperty("frontcache.jetty.webapp");

		System.out.println("Starting Frontcache Console Jetty Server (port=" + port + ", webapp=" + webDir + ") ...");

		Server server = new Server(port);

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

		System.out.println("Frontcache Console Jetty Server has been started successfully ...");

		server.join();
	}

}
