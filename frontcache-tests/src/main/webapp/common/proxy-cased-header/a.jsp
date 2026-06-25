<%
	// Simulate an intermediary (e.g. Cloudflare) that re-cases the component cache
	// directive on the origin response. FCHeaders uses "X-frontcache.component.maxage"
	// (lowercase f); here the header arrives title-cased. FC must parse it
	// case-insensitively and still cache the page.
	response.setHeader("X-Frontcache.Component.Maxage", "1h");
%>a