package org.frontcache.reqlog;

import java.io.IOException;
import java.io.InputStream;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.apache.logging.log4j.core.config.ConfigurationSource;
//import org.apache.logging.log4j.core.config.Configurator;
import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  Log requests to file (log4j2) for statistics and further analysis
 * 
 * 	REQUEST LOGGING FORMAT
 *  1 - true
 *  0 - false
 *  
 *  cacheable_flag - true/1 if request is run through FrontCache engine (e.g. GET method, text data). false/0 - otherwise (request forwarded to origin)
 *  dynamic_flag{1|0} - true if origin has been requested. false/0 - otherwise (it's cacheable & cached). 
 *  
 *	cacheable_flag{1|0}  dynamic_flag{1|0}   runtime_millis    datalength_bytes    url
 *
 */
public class RequestLogger {

	private final static String SEPARATOR = " ";

	private static Logger logger = LoggerFactory.getLogger(RequestLogger.class);
	

//	static {
//		
//		String configFileName = FCConfig.getProperty(CONFIG_FILE_KEY);
//		
//		if (null == configFileName)
//			configFileName = DEFAULT_CONFIG_FILE;
//		
//		InputStream reqLogConfigIS = FCConfig.getConfigInputStream(configFileName);
//		if (null != reqLogConfigIS)
//		{
//			ConfigurationSource source;
//			try {
//				source = new ConfigurationSource(reqLogConfigIS);
//		        Configurator.initialize(null, source);
//		        reqLogConfigIS.close();
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//		}
//        
//		logger = LogManager.getLogger(RequestLogger.class.getName());
//	}
	

	private RequestLogger() {
	}

	/**
	 * 
	 * @param url - request URL
	 * @param isCacheable - true if request is run through FrontCache engine (e.g. GET method, text data). false - otherwise (request forwarded to origin)
	 * @param isDynamic - true if origin has been requested. false/0 - otherwise (it's cacheable & cached).
	 * @param runtimeMillis - runtime is milliseconds
	 * @param lengthBytes - content length in bytes. 
	 */
	public static void logRequest(String url, boolean isCacheable, boolean isDynamic, long runtimeMillis, long lengthBytes) {

		StringBuilder sb = new StringBuilder();

		int isCacheableFlag = (isCacheable) ? 1 : 0;
		int isDynamicFlag = (isDynamic) ? 1 : 0;

		// FORMAT
		// dynamic_flag runtime_millis datalength_bytes url
		sb.append(isCacheableFlag)
		.append(SEPARATOR).append(isDynamicFlag)
		.append(SEPARATOR).append(runtimeMillis)
		.append(SEPARATOR).append(lengthBytes) 
		.append(SEPARATOR).append(url);

		logger.trace(sb.toString());

		return;
	}
}
