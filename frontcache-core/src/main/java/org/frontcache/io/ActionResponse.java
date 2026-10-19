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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Base of every management-API response.
 *
 * Unknown properties are ignored (inherited by every subclass), so a client can talk to a node of a
 * different version without a field it does not know about aborting the parse. This is not
 * hypothetical: PutToCacheActionResponse dropped its `domain` field when multi-domain support was
 * retired, and index replication has a new node reading an older node's response mid-upgrade.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionResponse {

	public static final String RESPONSE_STATUS_OK = "OK";
	public static final String RESPONSE_STATUS_ERROR = "ERROR";

	private String responseStatus;

	private String errorDescription;

	private String action;

	public ActionResponse() {
	}

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getResponseStatus() {
		return responseStatus;
	}

	public void setResponseStatus(String responseStatus) {
		this.responseStatus = responseStatus;
	}

	public String getErrorDescription() {
		return errorDescription;
	}

	public void setErrorDescription(String errorDescription) {
		this.errorDescription = errorDescription;
	}

}
