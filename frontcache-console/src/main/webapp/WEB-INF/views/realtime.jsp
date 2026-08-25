<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<html>
<head>
	<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
	<title>Frontcache console</title>

	<link rel="shortcut icon" href="static/images/favicon.ico" type="image/x-icon"/>

	<%-- the console-wide chrome: bootstrap's navbar needs jQuery, same pair as inc/header.jsp --%>
	<script src="<c:url value='https://ajax.googleapis.com/ajax/libs/jquery/2.2.2/jquery.min.js' />"></script>
	<script src="<c:url value='/static/js/bootstrap.js' />"></script>
	<link href="<c:url value='/static/css/app.css' />" rel="stylesheet"></link>
	<link href="<c:url value='/static/css/bootstrap.css' />" rel="stylesheet"></link>

	<!-- The realtime monitor. Uses none of the above - no d3, no underscore, no jQuery. -->
	<link href="<c:url value='/static/css/fc-dashboard.css' />" rel="stylesheet"></link>
	<link href="<c:url value='/static/css/fc-edges-status.css' />" rel="stylesheet"></link>
	<script src="<c:url value='/static/js/fc-dashboard.js' />"></script>
</head>

<body>
	<div id="mainWrapper">
		<%@ include file="/WEB-INF/views/inc/menu.jsp"%>

		<%-- fc-wide: the monitor needs the window's width, not bootstrap's 1170px --%>
		<div class="container fc-wide">
			<div class="fc-layout">
				<div class="fc-edges">
					<p style="height: 10px">&nbsp;</p>
					<%@ include file="/WEB-INF/views/inc/edges_status_stacked.jsp"%>
				</div>

				<div class="fc-monitor">
					<h2 style="font-size: 22px; padding-left: 20px;">Real time performance monitoring</h2>
					<div id="fc-monitor-content"></div>
				</div>
			</div>

			<%-- the edge list, as JSON, from IndexController --%>
			<script type="application/json" id="fc-dashboard-streams">${dashboardStreams}</script>
			<script>
				(function () {
					var streams = [];
					try {
						streams = JSON.parse(document.getElementById("fc-dashboard-streams").textContent);
					} catch (e) {
						console.error("Could not read the edge list", e);
					}
					FcDashboard.attach(document.getElementById("fc-monitor-content"), streams);
				})();
			</script>

			<hr>
			<footer>
				<p><a href="https://github.com/eternita/frontcache/blob/main/docs/doc-index.md" target="_blank">Frontcache Docs</a></p>
			</footer>
		</div>
	</div>
</body>
</html>
