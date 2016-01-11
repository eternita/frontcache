package org.frontcache.reqlog;

import java.io.IOException;
import java.io.InputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.frontcache.FCConfig;

/**
 *  Log requests to file (log4j2) for statistics and further analysis
 * 
 * 	REQUEST LOGGING FORMAT
 *	dynamic_flag{1|0}   runtime_millis    datalength_bytes    url
 *
 */
public class RequestLogger {

	private final static String SEPARATOR = " ";
	
	private final static String CONFIG_FILE_KEY = "request_logs_config";
	
	private final static String DEFAULT_CONFIG_FILE = "fc-log-config-default.xml";

	private static Logger logger = null;

	static {
		
		String configFileName = FCConfig.getProperty(CONFIG_FILE_KEY);
		
		if (null == configFileName)
			configFileName = DEFAULT_CONFIG_FILE;
		
		InputStream reqLogConfigIS = RequestLogger.class.getClassLoader().getResourceAsStream(configFileName);
		if (null != reqLogConfigIS)
		{
			ConfigurationSource source;
			try {
				source = new ConfigurationSource(reqLogConfigIS);
		        Configurator.initialize(null, source);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
        
		logger = LogManager.getLogger(RequestLogger.class.getName());
	}
	

	private RequestLogger() {
	}

	public static void logRequest(String url, boolean isDynamic, long runtimeMillis, long lengthBytes) {

		StringBuilder sb = new StringBuilder();

		int isDynamicFlag = (isDynamic) ? 1 : 0;

		// FORMAT
		// dynamic_flag runtime_millis datalength_bytes url
		sb.append(isDynamicFlag)
		.append(SEPARATOR).append(runtimeMillis)
		.append(SEPARATOR).append(lengthBytes) 
		.append(SEPARATOR).append(url);

		logger.trace(sb.toString());

		return;
	}
}
