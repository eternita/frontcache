<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="false"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%--
	The console's login page - see docs/console-login-ui.md section 7.

	This page deliberately does NOT include inc/header.jsp or inc/menu.jsp:

	  1. header.jsp loads jQuery from ajax.googleapis.com and Font Awesome from cdnjs.cloudflare.com.
	     A console on an isolated network has no route to either. Every other page degrades; this is
	     the one page that MUST render on a host with no internet, because without it nothing else
	     is reachable at all. So: one local stylesheet, no JavaScript, no CDN.
	  2. menu.jsp links to seven pages a signed-out visitor cannot open.
	  3. header.jsp declares pageEncoding="ISO-8859-1" with a UTF-8 meta tag.

	It also discloses nothing: no version, no edge list, no node names, no default user, and no word
	about whether the shipped default password is still in place. It is served to anyone who can
	reach the port. The default-password warning belongs after a successful login.

	session="false" is load-bearing and was measured, not guessed. A JSP touches the session by
	default, so rendering this page called getSession(true) - and this is the ONE page served
	without a credential (ConsoleAuthFilter.ANONYMOUS_PATHS), reached by every scanner and by the
	container's healthcheck every 30 seconds. That allocated a session object with an eight-hour
	timeout per anonymous hit, which is exactly what the filter's getSession(false) exists to
	prevent. Nothing here needs a session: "next", "error" and "submittedUser" are request
	attributes, and the sign-in itself happens in LoginController.
--%>
<%
	response.setHeader("Cache-Control", "no-store");
%>
<html>
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
		<meta name="viewport" content="width=device-width, initial-scale=1">
		<title>Frontcache console</title>
		<link rel="shortcut icon" href="<c:url value='/static/images/favicon.ico'/>" type="image/x-icon"/>
		<link href="<c:url value='/static/css/fc-login.css'/>" rel="stylesheet"></link>
	</head>

	<body>
		<div class="fc-login">
			<form class="fc-login-card" method="post" action="<c:url value='/login'/>">

				<img class="fc-login-logo" src="<c:url value='/static/images/frontcache-logo.png'/>"
					alt="Frontcache" width="150">

				<h1 class="fc-login-title">Console sign in</h1>

				<c:if test="${not empty error}">
					<p class="fc-login-error" role="alert"><c:out value="${error}"/></p>
				</c:if>

				<label class="fc-login-label" for="fc-user">User</label>
				<input class="fc-login-input" id="fc-user" name="user" type="text"
					autocomplete="username" autofocus required
					value="<c:out value='${submittedUser}'/>">

				<label class="fc-login-label" for="fc-password">Password</label>
				<input class="fc-login-input" id="fc-password" name="password" type="password"
					autocomplete="current-password" required>

				<input type="hidden" name="next" value="<c:out value='${next}'/>">

				<button class="fc-login-submit" type="submit">Sign in</button>
			</form>
		</div>
	</body>
</html>
