package org.frontcache.reqlog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *  Log requests to file (log4j2) for statistics and further analysis
 * 
 * 	REQUEST LOGGING FORMAT
 *	dynamic_flag{1|0}   runtime_millis    datalength_bytes    url
 *
 */
public class RequestLogger {

	private static String SEPARATOR = " ";

	private static Logger logger = LogManager.getLogger(RequestLogger.class.getName());

	private RequestLogger() {
	}

	public static void logRequest(String url, boolean isDynamic, long runtimeMillis, long lengthBytes) {

		StringBuilder sb = new StringBuilder();

		int isDynamicFlag = (isDynamic) ? 1 : 0;

		// FORMAT
		// dynamic_flag runtime_millis datalength_bytes url
		sb.append(isDynamicFlag)
		.append(SEPARATOR).append(runtimeMillis)
		.append(SEPARATOR).append(2 * lengthBytes) // x2 because each char is 2 bytes
		.append(SEPARATOR).append(url);

		logger.trace(sb.toString());

		return;
	}
}
