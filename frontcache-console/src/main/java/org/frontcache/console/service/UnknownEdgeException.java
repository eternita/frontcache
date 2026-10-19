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
package org.frontcache.console.service;

/**
 * A request named an edge that is not one of this console's configured edges - see
 * {@link FrontcacheService#configuredEdgeClient}. Thrown before any client is built, so nothing
 * carrying the API key is sent anywhere.
 *
 * An {@link IllegalArgumentException} because the edge is a request parameter: the controllers catch
 * it and put {@link #getMessage()} on the page.
 */
public class UnknownEdgeException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public UnknownEdgeException(String edgeURL) {
		super(messageFor(edgeURL));
	}

	/**
	 * The message, for callers that report a refusal rather than throw. The URL is the caller's, so a
	 * page rendering it must escape it.
	 */
	public static String messageFor(String edgeURL) {
		if (null == edgeURL || edgeURL.trim().isEmpty())
			return "No edge selected";

		return "Unknown edge " + edgeURL.trim() + " - not one of this console's edges";
	}
}
