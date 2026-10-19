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
package org.frontcache.console.plugin;

import java.util.List;

/**
 * A menu entry a {@link ConsolePlugin} adds.
 *
 * A class with getters and not a record: the JSPs read it through EL, which resolves JavaBeans
 * accessors - a record fails at render time with {@code PropertyNotFoundException}, the
 * {@code EdgeConfigFile} lesson.
 */
public final class NavEntry {

	/** A top-level tab in inc/menu.jsp. */
	public static final String TOP = "top";

	/** An entry in the Cache screens' submenu, inc/cache_menu.jsp. */
	public static final String CACHE = "cache";

	private final String section;
	private final String id;
	private final String label;
	private final String href;
	private final List<String> activeWhen;

	/**
	 * @param section     {@link #TOP} or {@link #CACHE}
	 * @param id          for {@link #CACHE}, the {@code activeCache} value its controller puts in the model;
	 *                    for {@link #TOP}, the {@code topActive} value its pages set
	 * @param href        relative to the console's context, like the core entries
	 * @param activeWhen  for {@link #TOP}: substrings of the request URI that mark this tab active
	 */
	public NavEntry(String section, String id, String label, String href, List<String> activeWhen) {
		this.section = section;
		this.id = id;
		this.label = label;
		this.href = href;
		this.activeWhen = (null == activeWhen) ? List.of() : List.copyOf(activeWhen);
	}

	public String getSection() {
		return section;
	}

	public String getId() {
		return id;
	}

	public String getLabel() {
		return label;
	}

	public String getHref() {
		return href;
	}

	public List<String> getActiveWhen() {
		return activeWhen;
	}
}
