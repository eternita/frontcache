/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 *
 * bots.conf and dynamic-urls.conf are node-wide.
 *
 * They used to be layered per domain: FCConfig scanned conf/ for subdirectories, treated each as a
 * domain, and looked up conf/&lt;domain&gt;/bots.conf with the top-level file as the fallback. One node
 * now serves one site, so each config is a single flat collection loaded from the top-level file.
 * See docs/archive/multi-domain-retirement-proposal.md.
 *
 * Fixture: src/test/resources/fallback-home/conf/{bots.conf,dynamic-urls.conf}, pinned as
 * -Dfrontcache.home by the frontcache-core test task.
 *
 */
public class FCConfigSingleSiteConfigTest {

	@Test
	public void botKeywordsComeFromTheTopLevelBotsConf() {

		Set<String> keywords = FCConfig.getBotUserAgentKeywords();

		assertEquals("unexpected bots.conf contents: " + keywords, 2, keywords.size());
		assertTrue(keywords.toString(), keywords.contains("Googlebot"));
		assertTrue(keywords.toString(), keywords.contains("bingbot"));
	}

	@Test
	public void commentsAndBlankLinesAreNotBotKeywords() {

		for (String keyword : FCConfig.getBotUserAgentKeywords())
		{
			assertFalse("comment leaked into bots.conf keywords: " + keyword, keyword.startsWith("#"));
			assertFalse("blank line leaked into bots.conf keywords", keyword.trim().isEmpty());
		}
	}

	@Test
	public void dynamicUrlPatternsAreCompiledFromTheTopLevelConf() {

		Set<Pattern> patterns = FCConfig.getDynamicURLPatterns();

		assertEquals("unexpected dynamic-urls.conf contents: " + patterns, 2, patterns.size());

		// the patterns are what ignoreCache() matches request URIs against
		assertTrue("/admin/users.htm should be dynamic", matchesAny(patterns, "/admin/users.htm"));
		assertTrue("/login.htm should be dynamic", matchesAny(patterns, "/login.htm?next=/"));
		assertFalse("/welcome.htm should be cacheable", matchesAny(patterns, "/welcome.htm"));
	}

	@Test
	public void dynamicUrlStringsMirrorThePatterns() {

		Set<String> urls = FCConfig.getDynamicURLs();
		Set<Pattern> patterns = FCConfig.getDynamicURLPatterns();

		// getDynamicURLs() is the console-facing view of the same set
		assertEquals(patterns.size(), urls.size());
		for (Pattern p : patterns)
			assertTrue("pattern missing from getDynamicURLs(): " + p, urls.contains(p.toString()));
	}

	private static boolean matchesAny(Set<Pattern> patterns, String uri) {
		for (Pattern p : patterns)
			if (p.matcher(uri).find())
				return true;

		return false;
	}

}
