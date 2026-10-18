/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.plugin;

import java.util.List;
import java.util.Set;

/**
 * A console plugin from its own jar - found with {@link java.util.ServiceLoader} through
 * {@code META-INF/services/org.frontcache.console.plugin.ConsolePlugin}. See
 * docs/ip-protection-apache-core-and-prop-pro-proposal.md section 4.5.
 *
 * <h2>What a plugin can and cannot do</h2>
 *
 * It contributes Spring beans to the console's two contexts, menu entries, scripts on the realtime page,
 * stylesheets, notices (banners and footer lines, through a {@link ConsoleNoticeProvider} bean) and
 * dashboard stream data (through a {@link DashboardStreamContributor} bean). Its controllers are ordinary
 * Spring MVC controllers behind the same {@code CharacterEncodingFilter}, {@code ConsoleAuthFilter} and
 * {@code SameOriginFilter} as every core path - all three are mapped {@code /*} in web.xml, which no
 * plugin can change - and <b>it cannot add an anonymous path</b>: {@code ConsoleAuthFilter.ANONYMOUS_PATHS}
 * is a closed, exact-match set with no extension point, and must stay one.
 *
 * <h2>One instance per process, cheap to construct</h2>
 *
 * Instantiated once by {@link ConsolePlugins}, before either Spring context exists. Everything it returns
 * here must be constant - it is read at startup and cached. Configuration is read by the plugin's own
 * beans from {@code FrontcacheService.getConsoleConfig()}.
 */
public interface ConsolePlugin {

	/** A short, stable id - for the startup log. */
	String id();

	/**
	 * Beans for the root context, the one {@code FrontcacheService} lives in: services. Each class is
	 * registered with {@code @Import}, so a {@code @Service} or {@code @Configuration} works as written.
	 *
	 * A plugin class may share a package the core scans (D4 keeps the packages of moved code). It is
	 * still one bean: Spring keeps the scanned definition and ignores the import of a class it already
	 * has. {@code ConsolePluginWiringTest} pins that, since a second bean of one type would fail every
	 * {@code @Autowired} of it as ambiguous - at console startup, not at build time.
	 */
	default List<Class<?>> rootComponents() {
		return List.of();
	}

	/** Beans for the dispatcher's context: controllers, controller advice, notice providers. */
	default List<Class<?>> webComponents() {
		return List.of();
	}

	/** Menu entries - a top-level tab or an entry in the Cache screens' submenu. */
	default List<NavEntry> navEntries() {
		return List.of();
	}

	/** Scripts the realtime page loads after {@code fc-dashboard.js} and before it attaches, as context-relative paths. */
	default List<String> dashboardScripts() {
		return List.of();
	}

	/** Stylesheets every page with the standard header loads, as context-relative paths. */
	default List<String> stylesheets() {
		return List.of();
	}

	/**
	 * The keys under {@code frontcache.console} this plugin reads. A console whose config sets a key that
	 * needs a plugin no plugin claims warns once at startup - see {@link ConsolePlugins#warnUnclaimedKeys}.
	 */
	default Set<String> configKeys() {
		return Set.of();
	}
}
