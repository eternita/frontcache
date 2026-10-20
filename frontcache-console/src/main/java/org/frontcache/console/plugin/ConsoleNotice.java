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

/**
 * One line a {@link ConsoleNoticeProvider} puts on every page: a banner under the menu, or a line in the
 * footer. A class with getters, for EL - see {@link NavEntry}.
 */
public final class ConsoleNotice {

	private final String level;
	private final String text;
	private final String href;
	private final String linkText;

	/**
	 * @param level     a Bootstrap alert level for a banner ({@code warning}, {@code danger}, ...); ignored in the footer
	 * @param href      a link after the text, or null
	 */
	public ConsoleNotice(String level, String text, String href, String linkText) {
		this.level = level;
		this.text = text;
		this.href = href;
		this.linkText = linkText;
	}

	public String getLevel() {
		return level;
	}

	public String getText() {
		return text;
	}

	public String getHref() {
		return href;
	}

	public String getLinkText() {
		return linkText;
	}
}
