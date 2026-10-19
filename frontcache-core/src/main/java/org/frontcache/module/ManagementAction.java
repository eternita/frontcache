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
package org.frontcache.module;

import java.io.IOException;

import org.frontcache.io.ActionResponse;

/**
 * One management action a {@link FrontcacheModule} answers on {@code /frontcache-io}.
 */
public interface ManagementAction {

	/** One line for the {@code help} action. */
	String description();

	/**
	 * @return the response the servlet writes as JSON, or null when the answer was streamed through
	 *         {@link ManagementRequest#openRawOutput}
	 */
	ActionResponse handle(ManagementRequest request) throws IOException;
}
