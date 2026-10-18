/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.plugin;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The root context's entry point for plugins - declared as a bean in {@code META-INF/spring-core-config.xml},
 * which is XML and so cannot carry an {@code @Import} of its own.
 */
@Configuration
@Import(ConsolePluginRootImports.class)
public class ConsolePluginRootConfig {
}
