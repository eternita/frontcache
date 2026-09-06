<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ taglib prefix="fn" uri="jakarta.tags.functions"%>

<!-- highlight (bold + underline) the top menu item matching the current page.
     Spring forwards to the JSP, so request.requestURI is the JSP path; the original
     client URL (e.g. /cache-view) is preserved in the standard forward request attribute. -->
<c:set var="uri" value="${empty requestScope['jakarta.servlet.forward.request_uri'] ? pageContext.request.requestURI : requestScope['jakarta.servlet.forward.request_uri']}"/>
<c:choose>
	<c:when test="${fn:contains(uri, 'realtime')}"><c:set var="topActive" value="realtime"/></c:when>
	<c:when test="${fn:contains(uri, 'fallbacks') or fn:contains(uri, 'bot-configs') or fn:contains(uri, 'dynamic-urls') or fn:contains(uri, 'resilience') or fn:contains(uri, 'hystrix') or fn:contains(uri, 'guard-configs')}"><c:set var="topActive" value="configs"/></c:when>
	<c:when test="${fn:contains(uri, 'cache-')}"><c:set var="topActive" value="cache"/></c:when>
	<c:when test="${fn:contains(uri, 'guard')}"><c:set var="topActive" value="guard"/></c:when>
	<c:when test="${fn:contains(uri, 'visitor-types')}"><c:set var="topActive" value="visitor"/></c:when>
	<c:when test="${fn:contains(uri, 'edges')}"><c:set var="topActive" value="edges"/></c:when>
</c:choose>

<style>
	#top .navbar-nav > li > a.active-top { font-weight: bold; text-decoration: underline; }
</style>

<nav class="navbar navbar-default navbar-static-top" id="top">
	<%-- the realtime monitor runs full-width (see .container.fc-wide in fc-dashboard.css);
	     the navbar follows so the logo stays aligned with the content below it --%>
	<div class="container ${topActive == 'realtime' ? 'fc-wide' : ''}">
		<div class="navbar-header">
			<button aria-controls="bs-navbar" aria-expanded="false"
				class="collapsed navbar-toggle" data-target="#bs-navbar"
				data-toggle="collapse" type="button">
				<span class="sr-only">Toggle navigation</span> <span
					class="icon-bar"></span> <span class="icon-bar"></span> <span
					class="icon-bar"></span>
			</button>
			<a href="index" class=""><img alt="Brand" style="padding-top: 5px; " src="static/images/frontcache-logo.png" width="150px"></a>
		</div>
		<nav class="collapse navbar-collapse" id="bs-navbar">
			<ul class="nav navbar-nav">
				<li><a href="realtime" class="${topActive == 'realtime' ? 'active-top' : ''}">Realtime monitor </a></li>
                <li><a href="fallbacks" class="${topActive == 'configs' ? 'active-top' : ''}">Configs </a></li>
                <li><a href="cache-view" class="${topActive == 'cache' ? 'active-top' : ''}">Cache </a></li>
                <li><a href="guard-rules" class="${topActive == 'guard' ? 'active-top' : ''}">Guard </a></li>
                <li><a href="visitor-types" class="${topActive == 'visitor' ? 'active-top' : ''}">Visitor Types </a></li>
				<li><a href="edges" class="${topActive == 'edges' ? 'active-top' : ''}">Edges </a></li>
			</ul>
		</nav>
	</div>
</nav>
