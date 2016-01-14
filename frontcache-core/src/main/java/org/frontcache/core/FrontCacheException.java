package org.frontcache.core;

@SuppressWarnings("serial")
public class FrontCacheException extends Exception {

	public FrontCacheException() {
	}

	public FrontCacheException(String message) {
		super(message);
	}

	public FrontCacheException(Throwable cause) {
		super(cause);
	}

	public FrontCacheException(String message, Throwable cause) {
		super(message, cause);
	}

	public FrontCacheException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
