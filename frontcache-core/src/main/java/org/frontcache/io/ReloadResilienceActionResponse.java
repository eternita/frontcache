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
package org.frontcache.io;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of {@code reload-resilience}.
 *
 * <b>The warnings are not decoration.</b> Applying that file without a restart costs circuit-breaker
 * state, and a changed thread-pool {@code maxQueueSize} cannot be applied at all - see
 * {@code FcResilienceReload}. A reload that reported neither would leave an operator believing the
 * live node matches its file when it does not.
 */
public class ReloadResilienceActionResponse extends ActionResponse {

	private List<String> warnings = new ArrayList<String>();

	public ReloadResilienceActionResponse() { // for JSON mapper
		setAction("Frontcache resilience configuration has been reloaded");
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(List<String> warnings) {
		this.warnings = (null == warnings) ? new ArrayList<String>() : warnings;
	}

}
