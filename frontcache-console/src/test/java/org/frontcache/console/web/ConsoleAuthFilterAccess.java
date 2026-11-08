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
package org.frontcache.console.web;

import org.frontcache.console.auth.ConsoleSecurity;

/** For tests in other packages: a filter over any {@link ConsoleSecurity}, without a servlet context. */
public final class ConsoleAuthFilterAccess {

	private ConsoleAuthFilterAccess() {
	}

	public static ConsoleAuthFilter filter(ConsoleSecurity security) {
		ConsoleAuthFilter filter = new ConsoleAuthFilter();
		filter.setConsoleSecurity(security);
		return filter;
	}
}
