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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts {@code ${consoleNotices}} - every {@link ConsoleNoticeProvider}'s banners and footer lines - into
 * every page's model, for inc/header.jsp and inc/footer.jsp. With no provider (a Community console) both
 * lists are empty and the pages render no banner and no footer line.
 */
@ControllerAdvice
public class ConsoleNoticesAdvice {

	private static final Logger logger = LoggerFactory.getLogger(ConsoleNoticesAdvice.class);

	@Autowired(required = false)
	private List<ConsoleNoticeProvider> providers = List.of();

	@ModelAttribute("consoleNotices")
	public ConsoleNotices consoleNotices() {

		ConsoleNotices all = new ConsoleNotices();
		for (ConsoleNoticeProvider provider : providers)
		{
			try {
				all.addAll(provider.notices());
			} catch (RuntimeException e) {
				logger.warn("A console notice provider failed - its notices are skipped", e);
			}
		}
		return all;
	}
}
