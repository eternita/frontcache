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
package org.frontcache.plugin.host;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * {@code frontcache-plugin-api} is what a plugin compiles against and what a plugin's own class loader will see:
 * it names <b>no engine class</b> - plugins see views and value types, never the engine's mutable objects - and no
 * library but Jackson's annotations and Typesafe Config's {@code Config} (a plugin's config is handed over parsed: a
 * stable, dependency-free library the core carries anyway). A plugin built against one {@code plugin-api} major must
 * survive core upgrades, which is only true while nothing of the engine leaks into its signatures.
 */
public class PluginApiBoundaryTest {

	private static final Path API_SOURCES = Paths.get("../frontcache-plugin-api/src/main/java");

	/**
	 * What an API source may import: the JDK, the API itself, ActionResponse (moved into it), Jackson's annotations
	 * and Typesafe Config's {@code Config} - nothing else of either library.
	 */
	private static final Pattern ALLOWED_IMPORT = Pattern.compile(
			"java\\.[\\w.]+|javax\\.annotation\\.[\\w.]+|org\\.frontcache\\.plugin(\\.[\\w.]+)?|org\\.frontcache\\.io\\.ActionResponse"
					+ "|com\\.fasterxml\\.jackson\\.annotation\\.[\\w.]+|com\\.typesafe\\.config\\.Config");

	/** A fully-qualified org.frontcache name written in code - an import or not. */
	private static final Pattern FRONTCACHE_NAME = Pattern.compile("\\borg\\.frontcache\\.[\\w.]+");

	private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)(\\.\\*)?\\s*;", Pattern.MULTILINE);

	private static final Pattern COMMENTS_AND_STRINGS = Pattern.compile(
			"//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);

	@Test
	public void theApiNamesNoEngineClassAndNoLibrary() throws IOException {

		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(API_SOURCES)) {
			walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
		}
		assertTrue("no sources under " + API_SOURCES.toAbsolutePath(), 10 < files.size());

		List<String> violations = new ArrayList<>();
		for (Path file : files)
		{
			String code = COMMENTS_AND_STRINGS.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll(" ");
			String relative = API_SOURCES.relativize(file).toString();

			Matcher imports = IMPORT.matcher(code);
			while (imports.find())
				if (!ALLOWED_IMPORT.matcher(imports.group(2)).matches())
					violations.add(relative + " imports " + imports.group(2));

			Matcher names = FRONTCACHE_NAME.matcher(code.replaceAll("(?m)^(package|import)\\s[^;]*;", " "));
			while (names.find())
			{
				String name = names.group();
				if (!name.startsWith("org.frontcache.plugin") && !name.equals("org.frontcache.io.ActionResponse"))
					violations.add(relative + " names " + name);
			}

			if (!relative.startsWith("org/frontcache/plugin/") && !relative.equals("org/frontcache/io/ActionResponse.java"))
				violations.add(relative + " is outside org.frontcache.plugin - the API's only other class is ActionResponse");
		}

		assertTrue("frontcache-plugin-api must name no engine class and no library (plugins see views, never the"
				+ " engine):\n  " + String.join("\n  ", violations), violations.isEmpty());
	}
}
