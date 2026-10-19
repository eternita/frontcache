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
package org.frontcache.resilience.fr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.frontcache.core.FcHttpClient;
import org.frontcache.core.WebResponse;
import org.junit.Test;

/**
 * {@code front-cache.fallback-resolver.impl} is an extension point: the class is named in an
 * operator-edited file and loaded by reflection, so it can live outside this jar and can have been
 * compiled against an older Frontcache. None of that loading had any test coverage.
 *
 * Two things about it are version-sensitive, and both fail in ways an operator would not connect to a
 * config file:
 *
 * <ul>
 * <li>The package moved in 2.7 ({@code org.frontcache.hystrix.fr} → {@code org.frontcache.resilience.fr}),
 *     so an un-edited config names a class that no longer exists.</li>
 * <li>The interface changed in 2.7 ({@code getFallbackConfigs()} returning {@code Map<domain, Set<...>>}
 *     became {@link FallbackResolver#getFallbackEntries()}). A resolver jar dropped in without
 *     recompiling still satisfies {@code instanceof} and loads cleanly, because the JVM does not
 *     re-verify a class against an interface that changed under it. The failure would be an
 *     {@code AbstractMethodError} on the first call - and for a fallback resolver, the first call
 *     happens while the origin is already down.</li>
 * </ul>
 *
 * One limit worth stating: the second scenario cannot be reproduced exactly in-process. A real 2.6
 * resolver DOES declare {@code implements FallbackResolver} and so passes {@code instanceof}; a fixture
 * here cannot, because this module compiles against the current interface and the compiler would demand
 * the method. Reproducing it faithfully would mean compiling against a stub interface and loading the
 * result against the real one. What is covered instead is the decision itself
 * ({@link FallbackResolverFactory#implementsGetFallbackEntries(Class)}, asserted directly on both
 * shapes and on both built-ins) and the fact that it runs before the class is constructed.
 */
public class FallbackResolverFactoryTest {

	/** The case that motivates the check: built for 2.6, so it has the old method and not the new one. */
	@Test
	public void aResolverBuiltForTheOldInterfaceIsRefused()
	{
		assertFalse("a class with only getFallbackConfigs() must not pass",
				FallbackResolverFactory.implementsGetFallbackEntries(PreTwoSevenResolver.class));

		assertEquals("it must not be loaded - the built-in resolver is used instead",
				FileBasedFallbackResolver.class,
				FallbackResolverFactory.resolverFor(PreTwoSevenResolver.class.getName()).getClass());
	}

	/**
	 * The refusal has to happen BEFORE the class is instantiated, which is the half of the behaviour the
	 * assertion above cannot distinguish - a class that never loads and a class that loads and is then
	 * discarded both end at the built-in resolver.
	 *
	 * So the fixture counts its own construction. It matters because construction is not free and not
	 * necessarily safe: {@code FileBasedFallbackResolver}'s constructor reads conf/fallbacks.conf, and a
	 * third-party one can do anything at all. A resolver we have already decided is unusable should not
	 * get to run its constructor first.
	 */
	@Test
	public void theCheckHappensBeforeTheClassIsInstantiated()
	{
		PreTwoSevenResolver.constructed = 0;

		FallbackResolverFactory.resolverFor(PreTwoSevenResolver.class.getName());

		assertEquals("a resolver rejected by the interface check must never be constructed",
				0, PreTwoSevenResolver.constructed);
	}

	@Test
	public void aCurrentResolverIsLoaded()
	{
		assertTrue(FallbackResolverFactory.implementsGetFallbackEntries(CurrentResolver.class));

		FallbackResolver loaded = FallbackResolverFactory.resolverFor(CurrentResolver.class.getName());

		assertEquals(CurrentResolver.class, loaded.getClass());
		assertTrue(loaded.getFallbackEntries().isEmpty());
	}

	/** Both built-ins have to pass their own check, or the shipped default would refuse to load. */
	@Test
	public void bothBuiltInResolversSatisfyTheCheck()
	{
		assertTrue(FallbackResolverFactory.implementsGetFallbackEntries(FileBasedFallbackResolver.class));
		assertTrue(FallbackResolverFactory.implementsGetFallbackEntries(DefaultFallbackResolver.class));
		assertFalse("the interface itself supplies nothing",
				FallbackResolverFactory.implementsGetFallbackEntries(FallbackResolver.class));
	}

	/**
	 * The pre-2.7 package name still resolves - an operator's frontcache.properties is not rewritten by
	 * the upgrade. Without this remap the node would log a Class.forName failure and quietly use the
	 * file-based resolver, which is the WRONG answer for anyone who had configured the other one.
	 */
	@Test
	public void theLegacyPackageNameIsRemapped()
	{
		assertEquals(DefaultFallbackResolver.class,
				FallbackResolverFactory.resolverFor("org.frontcache.hystrix.fr.DefaultFallbackResolver").getClass());

		assertEquals(FileBasedFallbackResolver.class,
				FallbackResolverFactory.resolverFor("org.frontcache.hystrix.fr.FileBasedFallbackResolver").getClass());
	}

	/** A name that is not a resolver at all, and one that is not a class at all. Neither may throw. */
	@Test
	public void unusableNamesFallBackToTheBuiltInResolver()
	{
		assertEquals("not a FallbackResolver", FileBasedFallbackResolver.class,
				FallbackResolverFactory.resolverFor(String.class.getName()).getClass());

		assertEquals("no such class", FileBasedFallbackResolver.class,
				FallbackResolverFactory.resolverFor("com.example.NoSuchResolver").getClass());

		assertEquals("a third-party package is NOT remapped", FileBasedFallbackResolver.class,
				FallbackResolverFactory.resolverFor("com.example.hystrix.fr.TheirResolver").getClass());
	}

	// ---- fixtures -----------------------------------------------------------------------------

	/**
	 * What a resolver compiled against 2.6 looks like at runtime. It deliberately does NOT declare
	 * {@code implements FallbackResolver} - it could not, since it lacks the current method, and that is
	 * precisely the situation: the compiler never got a chance to object, so the factory has to.
	 */
	public static class PreTwoSevenResolver {

		/** See {@link #theCheckHappensBeforeTheClassIsInstantiated()}. */
		static int constructed = 0;

		public PreTwoSevenResolver() {
			constructed++;
		}

		public void init(FcHttpClient client) { }

		public WebResponse getFallback(String fallbackSource, String urlStr) { return null; }

		public Map<String, Set<FallbackConfigEntry>> getFallbackConfigs() { return Collections.emptyMap(); }
	}

	public static class CurrentResolver implements FallbackResolver {

		@Override
		public void init(FcHttpClient client) { }

		@Override
		public WebResponse getFallback(String fallbackSource, String urlStr) { return null; }

		@Override
		public Set<FallbackConfigEntry> getFallbackEntries() { return Collections.emptySet(); }
	}

}
