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

/**
 * A resilience command's circuit breaker changed state.
 *
 * @param commandKey  the command, e.g. {@code Origin-Request}
 * @param from        {@code CLOSED}, {@code OPEN}, {@code HALF_OPEN}, ...
 * @param to          the state it changed to
 * @param failureRate the failure rate in percent when it changed, or -1 when not yet known
 */
public record CircuitStateChanged(long time, String node, String site, String commandKey, String from, String to,
		float failureRate) implements FcEvent {

	public static final String TYPE = "circuit.changed";

	@Override
	public String type() {
		return TYPE;
	}
}
