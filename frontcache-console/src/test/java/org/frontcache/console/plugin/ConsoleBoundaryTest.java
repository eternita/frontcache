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
package org.frontcache.console.plugin;

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
 * The console's half of the boundary between the Apache-2.0 core and Pro - frontcache-core's
 * {@code CoreBoundaryTest} is the node's. The Pro console plugin is frontcache-pro-console, which compiles
 * against this module and never the other way round. What the compiler cannot see is what this test is for:
 * a Pro class, view, script or services file put back into this module (the plugin shares the console's
 * package names, so it compiles), and a core JSP, script or stylesheet naming a Pro screen - that is text,
 * and fails only when the page renders.
 *
 * Pro code reaches the core only through {@code org.frontcache.console.plugin}; the core reaches Pro code
 * not at all.
 */
public class ConsoleBoundaryTest {

	private static final Path JAVA = Paths.get("src/main/java");
	private static final Path WEB = Paths.get("src/main/webapp/WEB-INF");

	/** The Pro console plugin's sources, beside this module in the private repository. */
	private static final Path PRO_JAVA = Paths.get("../frontcache-pro-console/src/main/java");

	/** Console classes that move to the Pro console plugin. */
	private static final Set<String> PRO_CONSOLE_CLASSES = Set.of(
			"org.frontcache.console.service.LicenseService",
			"org.frontcache.console.service.LicenseObservations",
			"org.frontcache.console.model.LicenseGroup",
			"org.frontcache.console.model.LicenseSummary",
			"org.frontcache.console.model.EdgeLicense",
			"org.frontcache.console.model.EdgeWarmer",
			"org.frontcache.console.web.controllers.LicenseController",
			"org.frontcache.console.web.controllers.CacheWarmerController");

	/** Whole packages that are Pro - the console plugin's own, and frontcache-core's Pro module. */
	private static final List<String> PRO_PACKAGES = List.of(
			"org.frontcache.console.pro.",
			"org.frontcache.pro.");

	/** frontcache-core's Pro classes outside org.frontcache.pro - see CoreBoundaryTest. */
	private static final Pattern CORE_PRO_REFERENCE = Pattern.compile(
			"\\borg\\.frontcache\\.(license\\.(?!BuildInfo\\b)\\w+"
			+ "|warmer\\.(?!WarmRequest\\b)\\w+"
			+ "|io\\.(WarmerActions|Warmer\\w*ActionResponse|LicenseActionResponse|UsageReportActionResponse))\\b");

	/** The Pro views and static files, and what a core page must not name. */
	private static final Set<String> PRO_WEB_FILES = Set.of(
			"views/license.jsp", "views/cache_warmer.jsp",
			"static/js/fc-warmer.js", "static/css/fc-warmer.css", "static/js/fc-dashboard-cluster.js");

	private static final List<String> PRO_WEB_TOKENS = List.of(
			"licenseSummary", "cache-warmer", "fc-warmer", "fc-dashboard-cluster", "ClusterRates",
			"href=\"license\"", "warmerControl");

	/** Every one of them moved to frontcache-pro-console; one that is back is Pro code in the core. */
	@Test
	public void noProClassViewOrScriptIsInTheCore() throws IOException {

		List<String> back = new ArrayList<String>();
		for (String fqn : sources(JAVA).keySet())
			if (isPro(fqn))
				back.add(fqn);
		for (String rel : PRO_WEB_FILES)
			if (Files.exists(WEB.resolve(rel)))
				back.add("WEB-INF/" + rel);
		if (Files.exists(Paths.get("src/main/resources/META-INF/services/" + ConsolePlugin.class.getName())))
			back.add("META-INF/services/" + ConsolePlugin.class.getName());

		assertTrue("Pro console code in frontcache-console - it belongs in frontcache-pro-console:\n  "
				+ String.join("\n  ", back), back.isEmpty());
	}

	@Test
	public void noCoreConsoleClassNamesAProClass() throws IOException {

		Map<String, Path> all = sources(JAVA);
		assertTrue("no sources under " + JAVA.toAbsolutePath(), all.size() > 10);

		Assume.assumeTrue("no Pro console plugin beside the console", Files.isDirectory(PRO_JAVA));
		Set<String> pro = new TreeSet<String>(sources(PRO_JAVA).keySet());
		assertTrue("the Pro set is what this test was written against: " + pro, pro.containsAll(PRO_CONSOLE_CLASSES));

		List<String> violations = new ArrayList<String>();
		for (Map.Entry<String, Path> e : all.entrySet())
		{
			String core = e.getKey();
			if (pro.contains(core))
				continue;

			String code = stripCommentsAndStrings(Files.readString(e.getValue(), StandardCharsets.UTF_8));
			String corePackage = core.substring(0, core.lastIndexOf('.'));

			for (String proClass : pro)
			{
				String simple = proClass.substring(proClass.lastIndexOf('.') + 1);
				boolean named = Pattern.compile("\\b" + Pattern.quote(proClass) + "\\b").matcher(code).find()
						|| (corePackage.equals(proClass.substring(0, proClass.lastIndexOf('.')))
								&& Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(code).find());
				if (named)
					violations.add(core + " -> " + proClass);
			}

			Matcher m = CORE_PRO_REFERENCE.matcher(code);
			while (m.find())
				violations.add(core + " -> " + m.group());
			for (String pkg : PRO_PACKAGES)
				if (code.contains(pkg))
					violations.add(core + " -> " + pkg + "*");
		}

		assertTrue("Core console classes name Pro code - go through org.frontcache.console.plugin instead:\n  "
				+ String.join("\n  ", violations), violations.isEmpty());
	}

	@Test
	public void noCorePageOrScriptNamesAProScreen() throws IOException {

		List<String> violations = new ArrayList<String>();
		try (Stream<Path> files = Files.walk(WEB)) {
			for (Path p : (Iterable<Path>) files::iterator)
			{
				String rel = WEB.relativize(p).toString().replace('\\', '/');
				if (!(rel.endsWith(".jsp") || rel.endsWith(".js") || rel.endsWith(".css")) || PRO_WEB_FILES.contains(rel))
					continue;
				if (rel.startsWith("static/js/bootstrap") || rel.startsWith("static/css/bootstrap"))
					continue;

				String text = Files.readString(p, StandardCharsets.UTF_8);
				for (String token : PRO_WEB_TOKENS)
					if (Pattern.compile("(?<![\\w-])" + Pattern.quote(token) + "(?![\\w])").matcher(text).find())
						violations.add(rel + " -> " + token);
			}
		}

		assertTrue("Core pages name a Pro screen - render it from fcPlugins / consoleNotices instead:\n  "
				+ String.join("\n  ", violations), violations.isEmpty());
	}

	private static Map<String, Path> sources(Path root) throws IOException {
		Map<String, Path> all = new TreeMap<String, Path>();
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				String rel = root.relativize(p).toString();
				all.put(rel.substring(0, rel.length() - 5).replace('/', '.').replace('\\', '.'), p);
			});
		}
		return all;
	}

	private static boolean isPro(String fqn) {
		if (PRO_CONSOLE_CLASSES.contains(fqn))
			return true;
		for (String pkg : PRO_PACKAGES)
			if (fqn.startsWith(pkg))
				return true;
		return false;
	}

	private static final Pattern COMMENTS_AND_STRINGS = Pattern.compile(
			"//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);

	private static String stripCommentsAndStrings(String code) {
		Matcher m = COMMENTS_AND_STRINGS.matcher(code);
		StringBuilder out = new StringBuilder();
		while (m.find())
			m.appendReplacement(out, " ");
		m.appendTail(out);
		return out.toString();
	}
}
