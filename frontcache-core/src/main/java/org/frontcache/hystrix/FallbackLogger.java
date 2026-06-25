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
package org.frontcache.hystrix;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;

import com.netflix.hystrix.HystrixEventType;

/**
 *
 * Marker for Logger
 *
 */
public class FallbackLogger {

	public static final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSZ");

	/**
	 * Maps Hystrix execution events to the failure category shown on the realtime dashboard
	 * (Short-Circuited | Bad Request | Timeout | Rejected | Failure). Used to tag failed
	 * requests in the dedicated failed-requests log.
	 *
	 * @param events the command's execution events (e.g. from getExecutionEvents())
	 * @return a lower-case category label, or "error" when none of the known events are present
	 */
	public static String failureType(List<HystrixEventType> events) {
		if (null == events)
			return "error";

		if (events.contains(HystrixEventType.SHORT_CIRCUITED))
			return "short-circuited";
		if (events.contains(HystrixEventType.TIMEOUT))
			return "timeout";
		if (events.contains(HystrixEventType.THREAD_POOL_REJECTED) || events.contains(HystrixEventType.SEMAPHORE_REJECTED))
			return "rejected";
		if (events.contains(HystrixEventType.BAD_REQUEST))
			return "bad-request";
		if (events.contains(HystrixEventType.FAILURE))
			return "failure";

		return "error";
	}

}
