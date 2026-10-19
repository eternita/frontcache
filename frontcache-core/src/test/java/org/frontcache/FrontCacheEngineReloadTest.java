/*
 * Copyright 2018 Eternita
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
package org.frontcache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import org.frontcache.bots.BotClassifier;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorManager;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The {@code reload} management action ({@link FrontCacheEngine#reload()}) rebuilds the engine.
 *
 * It used to take the node down instead: {@code stop()} called {@code FCConfig.destroy()}, which nulled
 * the properties, and FCConfig is initialized by its static initializer and nowhere else - so the
 * rebuilt engine's first {@code FCConfig.getProperty} threw NullPointerException, {@code reload()} left
 * no engine behind, and every later request tried to build one and threw again. dynamic-urls.conf was
 * emptied by the same call, which on its own would have quietly started caching URLs that must never be
 * cached.
 *
 * Fixing that exposed a second way the same call broke the node, quietly this time: {@code stop()} also
 * destroyed the cache and include processors, which are JVM-wide singletons that {@link CacheManager} and
 * {@link IncludeProcessorManager} then handed straight back to the rebuilt engine - Ehcache disposed, the
 * Lucene writer closed, every executor shut down. They are built from FCConfig, which a reload does not
 * re-read, so a reload keeps them and only a shutdown destroys them.
 *
 * Fixture: src/test/resources/fallback-home (NoopCacheProcessor, so building an engine is cheap).
 */
public class FrontCacheEngineReloadTest {

	@BeforeClass
	public static void pinTheFixtureHome() throws Exception {
		URL confMarker = FrontCacheEngineReloadTest.class.getClassLoader().getResource("fallback-home/conf/frontcache.properties");
		assertNotNull("fixture fallback-home/conf/frontcache.properties not found on test classpath", confMarker);

		File home = new File(confMarker.toURI()).getParentFile().getParentFile();
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, home.getAbsolutePath());

		BotClassifier.reload();
	}

	@AfterClass
	public static void stopTheEngine() {
		FrontCacheEngine.destroy();
	}

	@Test
	public void reloadRebuildsTheEngineAndKeepsTheConfig() {

		FrontCacheEngine before = FrontCacheEngine.getFrontCache();

		// FCConfig is a JVM-wide singleton loaded from whichever home was set when it was first touched,
		// so compare against itself rather than against the fixture's values
		Properties configBefore = FCConfig.getProperties();
		int dynamicURLsBefore = FCConfig.getDynamicURLPatterns().size();

		FrontCacheEngine.reload();

		FrontCacheEngine after = FrontCacheEngine.existingInstance();
		assertNotNull("reload left the node without an engine", after);
		assertNotSame("reload did not rebuild the engine", before, after);

		assertEquals("reload changed the node's frontcache.properties", configBefore, FCConfig.getProperties());
		assertEquals("reload emptied dynamic-urls.conf", dynamicURLsBefore, FCConfig.getDynamicURLPatterns().size());
	}

	@Test
	public void reloadKeepsTheCacheAndIncludeProcessorsAlive() throws Exception {

		FrontCacheEngine.getFrontCache();
		CacheProcessor cacheBefore = CacheManager.getInstance();
		IncludeProcessor includesBefore = IncludeProcessorManager.getInstance();

		FrontCacheEngine.reload();

		assertSame(cacheBefore, CacheManager.getInstance());
		assertSame(includesBefore, IncludeProcessorManager.getInstance());

		// NoopCacheProcessor (the fixture's) and ConcurrentIncludeProcessor (the default) both shut an
		// executor down in destroy(); a shut-down one rejects every soft refresh / include resolution
		assertFalse("reload destroyed the cache processor the new engine is using",
				executor(cacheBefore, "softInvalidationExecutor").isShutdown());
		assertFalse("reload destroyed the include processor the new engine is using",
				executor(includesBefore, "executor").isShutdown());
	}

	private static ExecutorService executor(Object owner, String fieldName) throws Exception {
		for (Class<?> c = owner.getClass(); null != c; c = c.getSuperclass())
		{
			try {
				Field field = c.getDeclaredField(fieldName);
				field.setAccessible(true);
				return (ExecutorService) field.get(owner);
			} catch (NoSuchFieldException e) {
				// declared further up
			}
		}
		throw new NoSuchFieldException(owner.getClass().getName() + "." + fieldName);
	}

	@Test
	public void reloadCanBeRepeated() {

		FrontCacheEngine.getFrontCache();

		FrontCacheEngine.reload();
		FrontCacheEngine.reload();

		assertNotNull("a second reload left the node without an engine", FrontCacheEngine.existingInstance());
	}
}
