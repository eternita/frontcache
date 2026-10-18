/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests;

import java.io.File;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;

/**
 * Embedded Jetty 12 (Jakarta EE10) launcher used by the e2e harness.
 *
 * Deploys a single exploded webapp at context "/" on a configurable port.
 * Driven by system properties:
 *   frontcache.jetty.port    - listen port (default 9080)
 *   frontcache.jetty.webapp  - path to the exploded webapp directory (or war)
 *
 * Used for both the FC standalone reverse-proxy (:9080) and the FC webfilter +
 * web app (:8080).
 */
public class FrontcacheStandaloneServer {

	public static void main(String[] args) throws Exception {

		int port = Integer.parseInt(System.getProperty("frontcache.jetty.port", "9080"));
		String webDir = System.getProperty("frontcache.jetty.webapp");

		System.out.println("Starting Frontcache Jetty Server (port=" + port + ", webapp=" + webDir + ") ...");

		Server server = new Server(port);

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath("/");
		webapp.setWar(new File(webDir).getAbsolutePath());
		// Surface startup failures instead of silently serving 404s.
		webapp.setThrowUnavailableOnStartupException(true);

		// Enable annotation scanning so ServletContainerInitializers are discovered,
		// including Jasper's JettyJasperInitializer (JSP) and the JSTL taglibs. The
		// JSP/JSTL implementations ship inside container (parent-classloader) jars,
		// not the webapp's WEB-INF/lib, so they must be named in the container-scan
		// pattern for their META-INF TLDs (e.g. jakarta.tags.core) to be found.
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
		System.out.println("StandaloneFrontcache started");

		server.join();
	}

}
