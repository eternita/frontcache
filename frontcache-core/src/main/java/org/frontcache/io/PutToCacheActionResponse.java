package org.frontcache.io;

public class PutToCacheActionResponse extends ActionResponse {


	public PutToCacheActionResponse() { // for json mapper
		setAction("put to cache");
		setResponseStatus(RESPONSE_STATUS_OK);
	}


}
