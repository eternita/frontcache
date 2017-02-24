package org.frontcache.core.tests;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FCUtilsTests {

	protected Logger logger = LoggerFactory.getLogger(FCUtilsTests.class);  


	@Test
	public void buildRequestURITest() throws Exception {
		
		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.coinshome.net:9080/fc/include-footer.htm?locale=en"));
		
		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.coinshome.net/fc/include-footer.htm?locale=en"));
		
		assertEquals("/ccc/veiw-catref-groups.htm?catrefFQ=catRef%3AKM%5C-2", FCUtils.buildRequestURI("http://origin.coinshome.net/ccc/veiw-catref-groups.htm?q=&catrefFQ=catRef:KM\\-2"));
		
		return;
	}
}
