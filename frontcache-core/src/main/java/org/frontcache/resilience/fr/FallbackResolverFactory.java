/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience.fr;


import org.apache.http.client.HttpClient;
import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class FallbackResolverFactory {

	private static Logger logger = LoggerFactory.getLogger(FallbackResolverFactory.class);
	private FallbackResolverFactory() {}

	private static FallbackResolver instance;

	public static FallbackResolver getInstance(){
		if (null == instance) {
			throw new RuntimeException("FallbackResolver is not initialized.");
		}
		return instance;
	}

	public static FallbackResolver init(HttpClient client){
		if (null == instance) {
			instance = getFallbackResolver();
			instance.init(client);
		}
		return instance;
	}

	/**
	 *
	 * @return
	 */
	private static FallbackResolver getFallbackResolver()
	{
		String implStr = FCConfig.getProperty("front-cache.fallback-resolver.impl");
		if (null == implStr)
		{
			logger.info("Default implementation is loaded: " + FileBasedFallbackResolver.class.getCanonicalName());
			return new FileBasedFallbackResolver();
		}

		implStr = remapLegacyPackage(implStr);

		try
		{

			@SuppressWarnings("rawtypes")
			Class clazz = Class.forName(implStr);
			Object obj = clazz.newInstance();
			if (null != obj && obj instanceof FallbackResolver)
			{
				logger.info("FallbackResolver implementation loaded: " + implStr);
				FallbackResolver fallbackResolver = (FallbackResolver) obj;

				return fallbackResolver;
			}
		} catch (Exception ex) {
			logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + FileBasedFallbackResolver.class.getCanonicalName());
			return new FileBasedFallbackResolver();
		}

		return new FileBasedFallbackResolver();
	}

	/**
	 * front-cache.fallback-resolver.impl is a fully-qualified class name in an operator-edited file, and
	 * the two built-in resolvers moved from org.frontcache.hystrix.fr to org.frontcache.resilience.fr in
	 * 2.7. Left alone, an upgraded node would fail Class.forName, log an error and silently fall back to
	 * FileBasedFallbackResolver - which happens to be the right answer for the shipped default and the
	 * wrong one for anybody who configured DefaultFallbackResolver, so the failure would be invisible
	 * until fallbacks were needed.
	 *
	 * Only the frontcache-owned prefix is rewritten; a third-party implementation is left untouched.
	 */
	private static String remapLegacyPackage(String implStr)
	{
		final String legacyPrefix = "org.frontcache.hystrix.fr.";
		if (!implStr.trim().startsWith(legacyPrefix))
			return implStr;

		String remapped = "org.frontcache.resilience.fr." + implStr.trim().substring(legacyPrefix.length());
		logger.warn("front-cache.fallback-resolver.impl=" + implStr + " names a package that was renamed in 2.7"
				+ " - loading " + remapped + " instead. Update frontcache.properties; this remapping is not permanent.");
		return remapped;
	}

	public static void destroy()
	{
		instance = null;
		return;
	}
}
