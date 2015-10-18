package org.frontcache.reqlog;

public class RequestLogger {
	
	private static String SEPARATOR = " ";

	private RequestLogger() {
	}

	public static void logRequest(String url, boolean isDynamic, long runtimeMillis)
	{
		StringBuilder sb = new StringBuilder();
		
		int isDynamicFlag = (isDynamic) ? 1 : 0; 
				
		// FORMAT
		// date dynamic_flag runtime_millis url
		sb.append(System.currentTimeMillis()).append(SEPARATOR).append(isDynamicFlag).append(SEPARATOR).append(runtimeMillis).append(SEPARATOR).append(url);
		
		System.out.println(sb.toString());
		return;
	}
}
