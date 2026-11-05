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
package org.frontcache.console.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A principal's choice of edge scopes, as of 2.12.0 - what {@link ConsoleEdgeScope#menu} answers and the navbar
 * renders as a dropdown ({@code fcScopeMenu}): the current scope's label, and the scopes the principal may switch to.
 * The core renders it and nothing more; what a scope is (a tenant, a site), and what an entry's link does, is the
 * edge scope's.
 *
 * A class with getters, not a record: the JSPs read it through EL, which reads JavaBeans getters only.
 */
public final class ScopeMenu {

	/** One line of the dropdown: a link to a scope, or - with no {@code href} - a heading above a group of them. */
	public static final class Entry {

		private final String label;
		private final String href;
		private final boolean current;
		private final boolean nested;

		/**
		 * @param label   shown as is (escaped)
		 * @param href    a console-relative link, or null for a heading
		 * @param current whether this is the request's scope
		 * @param nested  whether it is indented under the heading above it
		 */
		public Entry(String label, String href, boolean current, boolean nested) {
			this.label = Objects.requireNonNull(label, "label");
			this.href = href;
			this.current = current;
			this.nested = nested;
		}

		/** A heading - not a link. */
		public static Entry heading(String label) {
			return new Entry(label, null, false, false);
		}

		public String getLabel() {
			return label;
		}

		public String getHref() {
			return href;
		}

		public boolean isCurrent() {
			return current;
		}

		public boolean isNested() {
			return nested;
		}

		public boolean isHeading() {
			return null == href;
		}
	}

	private final String label;
	private final List<Entry> entries;

	/**
	 * @param label   the request's scope, as the navbar names it
	 * @param entries in display order
	 */
	public ScopeMenu(String label, List<Entry> entries) {
		this.label = Objects.requireNonNull(label, "label");
		this.entries = List.copyOf(null == entries ? new ArrayList<Entry>() : entries);
	}

	public String getLabel() {
		return label;
	}

	public List<Entry> getEntries() {
		return entries;
	}
}
