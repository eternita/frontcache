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
package org.frontcache.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;

/**
 * The boundary of docs/ip-protection-apache-core-and-prop-pro-proposal.md section 5.4. Since phase P2 the
 * Pro classes are in their own modules (frontcache-pro-license, frontcache-pro), which compile against the
 * core and never the other way round - so a core class naming one no longer compiles. What is left for this
 * test is what the compiler cannot see: a Pro class, or the module's services file, put back into the core
 * (D4 kept the package names, so a file dropped into org.frontcache.license or .warmer compiles fine), and
 * a core class naming a Pro class from the same split package.
 *
 * {@link #ALLOWED} listed the references P1 had not rewired yet; it is empty now, and an entry that no
 * longer matches anything fails the test too, so a fix cannot leave its permission behind.
 */
public class CoreBoundaryTest {

	private static final Path SOURCES = Paths.get("src/main/java");

	/** The Pro modules' sources, beside this module in the private repository (section 5.1). */
	private static final List<Path> PRO_SOURCES = List.of(
			Paths.get("../frontcache-pro-license/src/main/java"),
			Paths.get("../frontcache-pro/src/main/java"));

	/** Packages that move to Pro whole, and the classes in them that stay. */
	private static final Map<String, Set<String>> PRO_PACKAGES = Map.of(
			"org.frontcache.license", Set.of("BuildInfo"),
			"org.frontcache.pro", Set.of(),
			"org.frontcache.warmer", Set.of("WarmRequest"));

	/** Pro classes in a package that otherwise stays in the core. */
	private static final Set<String> PRO_CLASSES = Set.of(
			"org.frontcache.io.WarmerActions",
			"org.frontcache.io.WarmerStatusActionResponse",
			"org.frontcache.io.WarmerListsActionResponse",
			"org.frontcache.io.WarmerListActionResponse",
			"org.frontcache.io.WarmerPreflightActionResponse",
			"org.frontcache.io.LicenseActionResponse",
			"org.frontcache.io.UsageReportActionResponse");

	/**
	 * Core class -> the Pro classes it may still name. Empty since P1 finished (section 10.1):
	 * FrontCacheClient's warmer and license methods moved to ProFrontCacheClient (section 4.4). Keep it
	 * empty - a new entry is a core class reaching into Pro code, which stops compiling at the split.
	 */
	private static final Map<String, Set<String>> ALLOWED = Map.of();

	/** P2 moved every one of them out; one that is back is Pro code in the Apache core. */
	@Test
	public void noProClassIsInTheCore() throws IOException {

		List<String> back = new ArrayList<>();
		for (String fqn : sources(SOURCES).keySet())
			if (isPro(fqn))
				back.add(fqn);

		assertTrue("Pro classes in frontcache-core - they belong in frontcache-pro / frontcache-pro-license:\n  "
				+ String.join("\n  ", back), back.isEmpty());
		assertFalse("the Pro module's services file is in frontcache-core - it is frontcache-pro's",
				Files.exists(Paths.get("src/main/resources/META-INF/services/" + FrontcacheModule.class.getName())));
	}

	@Test
	public void noCoreClassNamesAProClass() throws IOException {

		Map<String, Path> all = sources(SOURCES);
		Set<String> pro = new TreeSet<>();
		for (Path dir : PRO_SOURCES)
			if (Files.isDirectory(dir))
				pro.addAll(sources(dir).keySet());
		Assume.assumeFalse("no Pro module beside the core", pro.isEmpty());
		assertTrue("the Pro set is what this test was written against: " + pro, pro.stream().allMatch(CoreBoundaryTest::isPro));

		List<String> violations = new ArrayList<>();
		Map<String, Set<String>> found = new TreeMap<>();

		for (Map.Entry<String, Path> e : all.entrySet())
		{
			String core = e.getKey();
			if (pro.contains(core))
				continue;

			String code = stripCommentsAndStrings(Files.readString(e.getValue(), StandardCharsets.UTF_8));
			String corePackage = packageOf(core);

			for (String proClass : pro)
			{
				if (!names(code, corePackage, proClass))
					continue;

				found.computeIfAbsent(core, k -> new TreeSet<>()).add(proClass);
				if (!ALLOWED.getOrDefault(core, Set.of()).contains(proClass))
					violations.add(core + " -> " + proClass);
			}
		}

		assertTrue("Core classes name Pro classes (docs/ip-protection-apache-core-and-prop-pro-proposal.md 3.1,"
				+ " principle 5 - go through the org.frontcache.module SPI instead):\n  "
				+ String.join("\n  ", violations), violations.isEmpty());

		List<String> stale = new ArrayList<>();
		for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet())
			for (String proClass : e.getValue())
				if (!found.getOrDefault(e.getKey(), Set.of()).contains(proClass))
					stale.add(e.getKey() + " -> " + proClass);

		assertTrue("Allowed references that no longer exist - remove them from ALLOWED:\n  "
				+ String.join("\n  ", stale), stale.isEmpty());
	}

	/** The core's own module SPI must not depend on anything Pro either - it is what Pro compiles against. */
	@Test
	public void theServicesFileIsTheOnlyPlaceTheProModuleIsNamed() throws IOException {

		Map<String, Path> all = sources(SOURCES);
		for (Map.Entry<String, Path> e : all.entrySet())
		{
			if (isPro(e.getKey()))
				continue;
			String code = stripCommentsAndStrings(Files.readString(e.getValue(), StandardCharsets.UTF_8));
			assertTrue(e.getKey() + " names ProModule", !code.contains("ProModule"));
		}
	}

	private static boolean isPro(String fqn) {

		if (PRO_CLASSES.contains(fqn))
			return true;

		Set<String> stays = PRO_PACKAGES.get(packageOf(fqn));
		return null != stays && !stays.contains(simpleName(fqn));
	}

	/** Named by its fully-qualified name (an import included), or by its simple name from its own package. */
	private static boolean names(String code, String fromPackage, String proClass) {

		if (Pattern.compile("\\b" + Pattern.quote(proClass) + "\\b").matcher(code).find())
			return true;

		return fromPackage.equals(packageOf(proClass))
				&& Pattern.compile("\\b" + Pattern.quote(simpleName(proClass)) + "\\b").matcher(code).find();
	}

	private static Map<String, Path> sources(Path root) throws IOException {

		Map<String, Path> byName = new TreeMap<>();
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				String relative = root.relativize(p).toString();
				String fqn = relative.substring(0, relative.length() - ".java".length()).replace('/', '.').replace('\\', '.');
				byName.put(fqn, p);
			});
		}
		assertTrue("no sources under " + root.toAbsolutePath(), !byName.isEmpty());
		return byName;
	}

	private static final Pattern COMMENTS_AND_STRINGS = Pattern.compile(
			"//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);

	static String stripCommentsAndStrings(String code) {
		Matcher m = COMMENTS_AND_STRINGS.matcher(code);
		StringBuilder out = new StringBuilder();
		while (m.find())
			m.appendReplacement(out, " ");
		m.appendTail(out);
		return out.toString();
	}

	private static String packageOf(String fqn) {
		int dot = fqn.lastIndexOf('.');
		return (dot < 0) ? "" : fqn.substring(0, dot);
	}

	private static String simpleName(String fqn) {
		return fqn.substring(fqn.lastIndexOf('.') + 1);
	}
}
