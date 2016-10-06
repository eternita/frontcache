<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1"%>
<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="display"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>

<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>Frontcache Console</title>


<script src="<c:url value='https://ajax.googleapis.com/ajax/libs/jquery/2.2.2/jquery.min.js' />"></script>
<script src="<c:url value='/static/js/bootstrap.js' />"></script>
<link href="<c:url value='/static/css/app.css' />" rel="stylesheet"></link>
<link href="<c:url value='/static/css/bootstrap.css' />" rel="stylesheet"></link>
</head>

<body>
	<div id="mainWrapper">
		<%@ include file="/WEB-INF/views/inc/menu.jsp"%>
		<div class="container">

			<div class="container">
				<div align="center">
				<p/><p/>
				No Frontcache Edges configured. <a href="edges">Add Edges here</a>
				<p/><p/>
				</div>


				<hr>
				<footer>
					<p>
						<a href="https://github.com/eternita/frontcache/wiki/Console-UI" target="_blank">Online documentation about Frontcache Console</a>
					</p>

				</footer>
			</div>

		</div>
	</div>
</body>
</html>