/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.example;

import java.io.File;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Embedded Jetty 12 (Jakarta EE10) launcher for running the Frontcache "JSP
 * servlet-filter" example locally. Deploys the built war at context "/", so
 * FrontCacheFilter and the fc.tld ship inside the webapp's WEB-INF/lib and are
 * discovered the same way a real container would find them.
 *
 * System properties:
 *   frontcache.jetty.port    - listen port (default 8080)
 *   frontcache.jetty.webapp  - path to the war (or exploded webapp directory)
 */
public class ExampleJettyServer {

	public static void main(String[] args) throws Exception {

		int port = Integer.parseInt(System.getProperty("frontcache.jetty.port", "8080"));
		String webDir = System.getProperty("frontcache.jetty.webapp");

		System.out.println("Starting Frontcache JSP example (port=" + port + ", webapp=" + webDir + ") ...");

		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
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

		System.out.println("Frontcache JSP example has been started successfully ...");
		System.out.println("Point your browser to http://localhost:" + port + "/example/index.jsp");

		server.join();
	}

}
