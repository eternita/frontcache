<%@ taglib uri="http://frontcache.org/core" prefix="fc" %><fc:component maxage="0" /><%
	// What the app received - in filter mode the app sees the raw servlet request, so this is what the
	// node in front of it put on the wire. See StandaloneEdgeTokenTests.
	String token = request.getHeader("x-frontcache-edge-token");
%>edge-token=[<%= (null == token) ? "absent" : token %>]
