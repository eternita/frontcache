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
package org.frontcache.plugin.event;

import java.util.List;

/**
 * A configuration file was (re)loaded.
 *
 * @param file    the file under {@code conf/}, e.g. {@code guard-rules.conf}
 * @param ok      false when the file was refused and the previous configuration stays
 * @param entries how many rules / entries are now in effect, or -1 when the file has no such count
 * @param reasons why entries were skipped or the file refused; empty when there were none
 */
public record ConfigReloaded(long time, String node, String site, String file, boolean ok, int entries,
		List<String> reasons) implements FcEvent {

	public static final String TYPE = "config.reloaded";

	public ConfigReloaded {
		reasons = List.copyOf(reasons);
	}

	@Override
	public String type() {
		return TYPE;
	}
}
