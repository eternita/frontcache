/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The config FILE was renamed in 2.7 (conf/hystrix.properties -> conf/resilience.properties) and the old
 * name is read for one more release. These tests drive that off a real FRONTCACHE_HOME on disk, because
 * the interesting part is not the parser - {@link FcResilienceConfigTest} covers that - but the file
 * lookup, and the lookup has a trap in it: {@code FCConfig.getConfigInputStream} reports "not found" by
 * THROWING, so a missing new file has to be caught rather than propagated or the legacy name is never
 * reached and the upgrade fails to start.
 */
public class FcResilienceConfigFileNameTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private String previousHome;
	private File conf;

	@Before
	public void setUp() throws IOException
	{
		previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		conf = tmp.newFolder("conf");
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, conf.getParentFile().getAbsolutePath());

		// FCConfig's static initializer reads conf/frontcache.properties from whatever home is set when
		// it is first touched, and throws if it is missing - so the fixture home needs one even though
		// nothing in these tests reads a property out of it.
		write("frontcache.properties", "front-cache.default-domain=localhost\n");

		FcResilienceConfig.reset();
	}

	@After
	public void tearDown()
	{
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);

		FcResilienceConfig.reset();
	}

	@Test
	public void readsTheNewFileName() throws IOException
	{
		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=1234");

		assertEquals(1234, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/** The upgrade path: the operator has not renamed the file yet, so their tuning must still apply. */
	@Test
	public void fallsBackToTheLegacyFileName() throws IOException
	{
		write("hystrix.properties", "hystrix.command.A.execution.isolation.thread.timeoutInMilliseconds=2345");

		assertEquals(2345, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/** Renaming the file without touching the keys inside it is enough - that is the documented advice. */
	@Test
	public void newFileNameWithLegacyKeysInsideIt() throws IOException
	{
		write("resilience.properties", "hystrix.command.A.execution.isolation.thread.timeoutInMilliseconds=3456");

		assertEquals(3456, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/**
	 * Both present: the new file wins outright and the legacy one is ignored, not merged. Asserted
	 * because a union of the two would be a config nobody could reason about, and because "I renamed it
	 * but my old value is still winning" is the bug report that would follow the other choice.
	 */
	@Test
	public void newFileNameWinsOverTheLegacyOneAndTheyAreNotMerged() throws IOException
	{
		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=4567");
		write("hystrix.properties",
				"resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=9999\n"
				+ "resilience.command.B.execution.isolation.thread.timeoutInMilliseconds=8888");

		FcResilienceConfig config = FcResilienceConfig.getInstance();
		assertEquals(4567, config.forCommand("A", null).getTimeoutMs());
		assertEquals("a key only the legacy file sets must NOT leak in",
				FcResilienceConfig.DEFAULT_TIMEOUT_MS, config.forCommand("B", null).getTimeoutMs());
	}

	/**
	 * Neither file present. This must be built-in defaults rather than a thrown exception: the config
	 * singleton is first touched on the first request, so throwing here would turn a missing tuning file
	 * into a node that answers 500s instead of one that runs with defaults.
	 */
	@Test
	public void noFileAtAllMeansBuiltInDefaults()
	{
		assertEquals(FcResilienceConfig.DEFAULT_TIMEOUT_MS,
				FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	private void write(String name, String content) throws IOException
	{
		FileWriter writer = new FileWriter(new File(conf, name));
		try {
			writer.write(content);
		} finally {
			writer.close();
		}
	}
}
