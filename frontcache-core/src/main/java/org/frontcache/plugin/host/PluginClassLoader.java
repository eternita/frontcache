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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * The class loader of one dropped-in plugin: its own jars, over a parent that shows <b>only the plugin API</b> - never
 * the engine, and never another plugin. Parent-first, so a plugin cannot replace an API class.
 *
 * <p>What the parent shows ({@link #visible}): the JDK; the API's packages {@code org.frontcache.plugin},
 * {@code .event}, {@code .serve}, {@code .decide} and {@code org.frontcache.io.ActionResponse}; and the libraries the API
 * hands a plugin or shares with it - Typesafe Config (a plugin's config), Jackson's annotations (on
 * {@code ActionResponse}), slf4j (the node's logging) and Micrometer ({@code bindMeters}). Anything else a plugin needs
 * - Jackson's databind included - is in its own jars, at whatever version it likes: two plugins may carry two versions
 * of one library. A core class ({@code FrontCacheEngine}, {@code RequestContext}, the core's side of the plugin
 * packages, {@code org.frontcache.plugin.host}) is a {@link ClassNotFoundException} to it.
 */
final class PluginClassLoader extends URLClassLoader {

	static {
		registerAsParallelCapable();
	}

	/** The API's own packages - exactly these, not their sub-packages. */
	static final Set<String> API_PACKAGES = Set.of("org.frontcache.plugin", "org.frontcache.plugin.event",
			"org.frontcache.plugin.serve", "org.frontcache.plugin.decide");

	/** The one API class in a package the API shares with the core. */
	static final String ACTION_RESPONSE = "org.frontcache.io.ActionResponse";

	/** The libraries a plugin shares with the node. */
	static final List<String> LIBRARY_PREFIXES = List.of("com.typesafe.config.", "com.fasterxml.jackson.annotation.",
			"org.slf4j.", "io.micrometer.");

	PluginClassLoader(String id, URL[] jars, ClassLoader core) {
		super("frontcache-plugin-" + id, jars, new ApiView(core));
	}

	/** Whether a plugin may see a class of the core's loader by this name. */
	static boolean visible(String name) {
		for (String prefix : LIBRARY_PREFIXES)
			if (name.startsWith(prefix))
				return true;
		if (name.equals(ACTION_RESPONSE) || name.startsWith(ACTION_RESPONSE + "$"))
			return true;
		int dot = name.lastIndexOf('.');
		return 0 < dot && API_PACKAGES.contains(name.substring(0, dot));
	}

	/**
	 * The parent: the platform loader first (the JDK), then the core's loader for what {@link #visible} admits, then a
	 * JDK module the platform loader does not define (some are the application loader's). Nothing else - and no
	 * resource of the core's, so a plugin's {@code getResources} sees its own manifest only.
	 */
	private static final class ApiView extends ClassLoader {

		static {
			registerAsParallelCapable();
		}

		private final ClassLoader core;

		ApiView(ClassLoader core) {
			super("frontcache-plugin-api", ClassLoader.getPlatformClassLoader());
			this.core = core;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (visible(name))
				return core.loadClass(name);
			Class<?> jdk;
			try {
				jdk = ClassLoader.getSystemClassLoader().loadClass(name);
			} catch (ClassNotFoundException | LinkageError e) {
				throw new ClassNotFoundException(name);
			}
			Module module = jdk.getModule();
			if (module.isNamed() && ModuleLayer.boot() == module.getLayer())
				return jdk;
			throw new ClassNotFoundException(name + " is not part of the plugin API - a plugin carries it in its own jar");
		}

		@Override
		protected URL findResource(String name) {
			return null;
		}

		@Override
		protected Enumeration<URL> findResources(String name) throws IOException {
			return Collections.emptyEnumeration();
		}
	}
}
