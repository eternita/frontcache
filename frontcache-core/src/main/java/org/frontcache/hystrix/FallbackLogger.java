package org.frontcache.hystrix;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

/**
 * 
 * Marker for Logger
 *
 */
public class FallbackLogger {
	
	public static final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSZ");

}
