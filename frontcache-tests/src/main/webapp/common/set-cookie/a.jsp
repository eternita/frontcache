<%@ taglib uri="http://frontcache.org/core" prefix="fc" %><%@ page session="false" %><%
	// A cacheable page that also sets a cookie - e.g. a page the app marks cacheable while still
	// stamping a per-visitor cookie on it. The cookie must reach the visitor whose request produced
	// it (cache miss), but must NOT be stored in the cache and replayed to every later visitor
	// (cache hit).
	response.setHeader("Set-Cookie", "fc-test-cookie=visitor-" + System.currentTimeMillis() + "; Path=/");
%><fc:component maxage="1h" />a