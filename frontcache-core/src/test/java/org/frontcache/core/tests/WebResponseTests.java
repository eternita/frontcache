package org.frontcache.core.tests;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.core.WebResponse;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class WebResponseTests {

	protected Logger logger = LoggerFactory.getLogger(WebResponseTests.class);  


	/**
	 * Tests WebResponse serialization/de-serialization to/from JSON
	 * 
	 * @throws Exception
	 */
	@Test
	public void jsonSelializationTest() throws Exception {
		
		ObjectMapper jsonMapper = new ObjectMapper();
		
		WebResponse webResponse = new WebResponse("http://localhost:9080/en/welcome.htm", "some text".getBytes());

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		List<String> myList = new ArrayList<String>();
		myList.add("ab1");
		myList.add("ab2");
		headers.put("header1", myList);
		
		myList = new ArrayList<String>();
		myList.add("ab3");
		myList.add("ab4");
		headers.put("header2", myList);
		webResponse.setHeaders(headers);
		
		
		String webResponseJSONStr1 = jsonMapper.writeValueAsString(webResponse);
		// {"statusCode":-1,"url":"http://localhost:9080/en/welcome.htm","content":"c29tZSB0ZXh0","headers":{"header2":["ab3","ab4"],"header1":["ab1","ab2"]},"tags":null,"contentType":null,"contentLenth":9,"cacheable":false,"expired":false,"text":false}
		logger.info("web response 1 : " + webResponseJSONStr1);
		
		WebResponse webResponse2 = jsonMapper.readValue(webResponseJSONStr1.getBytes(), WebResponse.class);
		String webResponseJSONStr2 = jsonMapper.writeValueAsString(webResponse2);
		
		logger.info("web response 1 : " + webResponseJSONStr2);
		
		assertEquals(webResponseJSONStr1, webResponseJSONStr2);
		return;
	}
}
