/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import java.util.Map;

public class HelpActionResponse extends ActionResponse {

	private Map<String, String> actionsDescription = null;
	
	public HelpActionResponse() {
		setAction("help");
		setResponseStatus(RESPONSE_STATUS_OK);
	}
	
	public HelpActionResponse(Map<String, String> actionsDescription) {
		this();
		this.actionsDescription = actionsDescription;
	}
	
	public Map<String, String> getActionsDescription() {
		return actionsDescription;
	}

}
