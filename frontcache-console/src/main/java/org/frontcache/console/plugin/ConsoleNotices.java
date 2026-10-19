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

import java.util.ArrayList;
import java.util.List;

/** Every page's banners and footer lines, as inc/header.jsp and inc/footer.jsp render them ({@code ${consoleNotices}}). */
public final class ConsoleNotices {

	private final List<ConsoleNotice> banners = new ArrayList<ConsoleNotice>();
	private final List<ConsoleNotice> footerLines = new ArrayList<ConsoleNotice>();

	public List<ConsoleNotice> getBanners() {
		return banners;
	}

	public List<ConsoleNotice> getFooterLines() {
		return footerLines;
	}

	void addAll(ConsoleNotices other) {
		if (null == other)
			return;
		banners.addAll(other.banners);
		footerLines.addAll(other.footerLines);
	}
}
