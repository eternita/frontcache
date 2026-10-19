/*
 * Copyright 2015 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.resilience.fr;


import org.frontcache.core.FcHttpClient;
import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class FallbackResolverFactory {

	private static Logger logger = LoggerFactory.getLogger(FallbackResolverFactory.class);
	private FallbackResolverFactory() {}

	private static volatile FallbackResolver instance;

	/** Whether the origin warm-up leg of {@link #init} has been attempted. */
	private static volatile boolean originWarmupAttempted;

	/**
	 * The resolver, creating it if this is the first call.
	 *
	 * This used to throw {@code RuntimeException("FallbackResolver is not initialized.")} whenever it
	 * ran before {@link #init}, and {@link org.frontcache.FrontCacheEngine} calls {@code init} from a
	 * background thread after a deliberate 3-second sleep - so for the first three seconds of a node's
	 * life every caller that needed a fallback got an exception instead of one. The callers are
	 * {@code FC_Total}, {@code FC_BypassCache}, both {@code FC_ThroughCache_*} and the include
	 * processor: that is, the code whose whole job is to absorb a failure. It therefore turned one
	 * failed fragment into a failed page, and did it precisely during a restart, when a proxy is at
	 * its least healthy. On fc-edge it read as 24% of {@code Input-Request} failing on a node that had
	 * been up 17 seconds.
	 *
	 * Nothing about the sleep required the throw. Creating the resolver reads {@code fallbacks.conf}
	 * from disk and needs no server - {@code FileBasedFallbackResolver}'s constructor does it. Only
	 * {@link FallbackResolver#init}'s origin warm-up needs the node to be listening, which is what the
	 * sleep is for. Splitting the two means the file-based fallbacks are answerable from the first
	 * request, and the URL-sourced ones fill in when the node is up.
	 */
	public static FallbackResolver getInstance(){

		FallbackResolver local = instance;
		if (null != local)
			return local;

		synchronized (FallbackResolverFactory.class) {
			if (null == instance)
				instance = getFallbackResolver(); // published only once fully constructed
			return instance;
		}
	}

	/**
	 * Creates the resolver if needed and runs its origin warm-up once.
	 *
	 * The warm-up is guarded separately from creation because this is also the console's reload path
	 * ({@code FrontCacheIOServlet} calls it on every {@code get-fallback-configs}), and re-fetching
	 * every fallback from the origin on a dashboard poll is not what that endpoint means. The flag is
	 * set in a {@code finally} so a warm-up that throws is not retried on every subsequent poll, and
	 * cleared by {@link #destroy}, which is what makes the servlet's destroy-then-init actually reload.
	 */
	public static FallbackResolver init(FcHttpClient client){

		FallbackResolver local = getInstance();

		if (originWarmupAttempted)
			return local;

		synchronized (FallbackResolverFactory.class) {
			if (originWarmupAttempted)
				return local;
			try {
				local.init(client);
			} finally {
				originWarmupAttempted = true;
			}
		}
		return local;
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

		return resolverFor(implStr);
	}

	/**
	 * Loads the named resolver, or the built-in one if it cannot be used.
	 *
	 * Split out from the config lookup above so this - the part with the reflection, the legacy package
	 * remap and the interface check - can be tested without a FRONTCACHE_HOME. {@code FCConfig} reads
	 * its properties once in a static initializer and offers no reload, so a test that had to go through
	 * the config to reach this code could not vary the class name at all.
	 *
	 * Package-private for that reason.
	 */
	static FallbackResolver resolverFor(String configuredClassName)
	{
		String implStr = remapLegacyPackage(configuredClassName);

		try
		{
			Class<?> clazz = Class.forName(implStr);

			if (!implementsGetFallbackEntries(clazz))
			{
				logger.error("front-cache.fallback-resolver.impl=" + implStr + " does not implement"
						+ " getFallbackEntries(). It was almost certainly compiled against Frontcache 2.6, where"
						+ " FallbackResolver declared getFallbackConfigs() returning Map<domain, Set<...>> instead;"
						+ " the domain key went away with multi-domain support. Recompile it against this release."
						+ " Falling back to " + FileBasedFallbackResolver.class.getCanonicalName() + ".");
				return new FileBasedFallbackResolver();
			}

			Object obj = clazz.getDeclaredConstructor().newInstance();
			if (obj instanceof FallbackResolver)
			{
				logger.info("FallbackResolver implementation loaded: " + implStr);
				return (FallbackResolver) obj;
			}

			logger.error(implStr + " is not a " + FallbackResolver.class.getCanonicalName()
					+ ". Default implementation is loaded: " + FileBasedFallbackResolver.class.getCanonicalName());

		} catch (Exception ex) {
			logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + FileBasedFallbackResolver.class.getCanonicalName(), ex);
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

	/**
	 * Whether the loaded class actually supplies {@code getFallbackEntries()}, or merely inherits the
	 * interface's abstract declaration.
	 *
	 * This check exists because the JVM does not re-verify a class against an interface that changed
	 * after it was compiled. A resolver built against 2.6 still passes {@code instanceof
	 * FallbackResolver} here and loads cleanly; the failure - an {@code AbstractMethodError} - would
	 * arrive later, on the first call. For a fallback resolver that call happens while the origin is
	 * already down, which is the worst moment to discover a packaging mistake. Better to refuse it at
	 * startup and say exactly what to do.
	 *
	 * {@code getMethod} searches superclasses and superinterfaces, so a class that does not supply the
	 * method resolves to the interface's own abstract declaration - which is what the interface check
	 * detects.
	 */
	static boolean implementsGetFallbackEntries(Class<?> clazz)
	{
		try {
			return !clazz.getMethod("getFallbackEntries").getDeclaringClass().isInterface();
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	public static void destroy()
	{
		synchronized (FallbackResolverFactory.class) {
			instance = null;
			originWarmupAttempted = false;
		}
		return;
	}
}
