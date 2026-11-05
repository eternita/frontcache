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

import org.frontcache.console.auth.EdgeScope;

/**
 * A bean - in either context - that contributes to every page's banners and footer lines.
 *
 * Called once per MVC request, {@code /edges-status.json} polls included, so it must answer from state it
 * already holds and never fan out to the edges. A provider implements one of the two methods: {@link #notices()}
 * for notices that are the same on every page, {@link #notices(EdgeScope)} for ones about the request's edges.
 */
public interface ConsoleNoticeProvider {

	/** The notices of every page. The default has none. */
	default ConsoleNotices notices() {
		return new ConsoleNotices();
	}

	/**
	 * The notices of a page whose request reaches {@code scope}'s edges, as of 2.12.0 - for a provider whose notices
	 * are about edges, which must never be about edges outside the request's scope. The default is {@link #notices()}.
	 *
	 * @param scope the request's scope - empty on a page served without a principal (the login page)
	 */
	default ConsoleNotices notices(EdgeScope scope) {
		return notices();
	}
}
