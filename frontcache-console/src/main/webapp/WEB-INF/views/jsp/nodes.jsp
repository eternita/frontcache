<%@ taglib prefix="spring" uri="http://www.springframework.org/tags"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<!DOCTYPE html>
<html lang="en">
<head>
<title>Frontcache Console</title>

<spring:url value="/resources/core/css/console.css" var="coreCss" />
<spring:url value="/resources/core/css/bootstrap.min.css" var="bootstrapCss" />
<link href="${bootstrapCss}" rel="stylesheet" />
<link href="${coreCss}" rel="stylesheet" />
</head>

<nav class="navbar navbar-inverse navbar-fixed-top">
	<div class="container">
		<div class="navbar-header">
			<a class="navbar-brand" href="#">Frontcache Console</a>
		</div>
	</div>
</nav>

<div class="container">

	<div class="row">
		<div class="col-md-12">
		<p style="height: 100px">&nbsp;</p>
		Amount of cached items on each server <br/><br/>
		
			<c:forEach var="entry" items="${cachedAmount}">
			  <c:out value="${entry.key}"/> - 
			  <c:out value="${entry.value}"/> items <br/>
			</c:forEach>
		</div>
	</div>
    Real time monitoring :<a target="_blank" href="${hystrixMonitorURL}">Hystrix Dashboard</a>  

	<hr>
	<footer>
		<p>&copy; frontcache.org</p>
	</footer>
</div>

<script src="https://ajax.googleapis.com/ajax/libs/jquery/1.11.2/jquery.min.js"></script>
<spring:url value="/resources/core/js/console.js" var="coreJs" />
<spring:url value="/resources/core/js/bootstrap.min.js" var="bootstrapJs" />

<script src="${coreJs}"></script>
<script src="${bootstrapJs}"></script>

</body>
</html>