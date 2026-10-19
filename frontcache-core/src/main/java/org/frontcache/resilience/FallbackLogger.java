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
package org.frontcache.resilience;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Marker for Logger
 *
 */
public class FallbackLogger {

	public static final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSZ");

	/** The pre-2.7 name of the logger below, which conf/fc-logback.xml routes to logs/fallback.log. */
	private static final String LEGACY_LOGGER_NAME = "org.frontcache.hystrix.FallbackLogger";

	private static final Logger logger = LoggerFactory.getLogger(FallbackLogger.class);

	private static final AtomicBoolean staleLogbackConfigWarned = new AtomicBoolean(false);

	/**
	 * The logger the fallback resolvers write their TRACE lines to.
	 *
	 * Renaming this class's package renamed the logger with it, and a logger name is config, not code:
	 * an fc-logback.xml carried over from 2.6 still routes the OLD name to logs/fallback.log, so the
	 * new lines would fall through to the root logger, be dropped at its level, and leave fallback.log
	 * silently empty. That is the worst possible failure mode for a diagnostic log - it looks like
	 * "no fallbacks happened".
	 *
	 * So detect it: the legacy name being TRACE-enabled while the current one is not can only mean a
	 * config file that still names the old logger. Both being off is someone turning fallback logging
	 * off deliberately, which is not worth a warning.
	 */
	public static Logger logger()
	{
		if (!logger.isTraceEnabled()
				&& LoggerFactory.getLogger(LEGACY_LOGGER_NAME).isTraceEnabled()
				&& staleLogbackConfigWarned.compareAndSet(false, true))
			logger.warn("conf/fc-logback.xml still declares the logger '" + LEGACY_LOGGER_NAME + "', renamed in 2.7."
					+ " Fallback TRACE lines are going nowhere and logs/fallback.log will stay empty."
					+ " Rename that logger to '" + FallbackLogger.class.getName() + "'.");

		return logger;
	}

	/**
	 * Maps command execution events to the failure category shown on the realtime dashboard
	 * (Short-Circuited | Bad Request | Timeout | Rejected | Failure). Used to tag failed
	 * requests in the dedicated failed-requests log.
	 *
	 * The returned strings are deliberately unchanged by the Resilience4j migration: they land in the
	 * failed-requests log and in {@code RequestLogger}'s positional output, which log tooling parses.
	 * Only the parameter type moved from {@code List<HystrixEventType>} to {@code List<FcEventType>},
	 * and {@link FcEventType} keeps the same constant names so the mapping is one-for-one.
	 *
	 * @param events the command's execution events (e.g. from getExecutionEvents())
	 * @return a lower-case category label, or "error" when none of the known events are present
	 */
	public static String failureType(List<FcEventType> events) {
		if (null == events)
			return "error";

		if (events.contains(FcEventType.SHORT_CIRCUITED))
			return "short-circuited";
		if (events.contains(FcEventType.TIMEOUT))
			return "timeout";
		if (events.contains(FcEventType.THREAD_POOL_REJECTED) || events.contains(FcEventType.SEMAPHORE_REJECTED))
			return "rejected";
		if (events.contains(FcEventType.BAD_REQUEST))
			return "bad-request";
		if (events.contains(FcEventType.FAILURE))
			return "failure";

		return "error";
	}

}
