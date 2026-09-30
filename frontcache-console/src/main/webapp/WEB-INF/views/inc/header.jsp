<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%
	// Every page that includes this header is dynamic and behind a login, so none of them may come
	// back from the browser's cache after a sign-out - which is reachable for the first time now
	// that there is a sign-out. Static assets under /static are unaffected: they are not served
	// through a JSP. See docs/archive/console-login-ui.md section 8.
	response.setHeader("Cache-Control", "no-store");
%>
<html>
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
		<title>Frontcache console</title>
		<link rel="shortcut icon" href="static/images/favicon.ico" type="image/x-icon"/> 
		<link href="<c:url value='/static/css/bootstrap.css' />"  rel="stylesheet"></link>
		<script src="<c:url value='https://ajax.googleapis.com/ajax/libs/jquery/2.2.2/jquery.min.js' />"></script>
		 <script src="<c:url value='/static/js/bootstrap.js' />"></script>
		<link href="<c:url value='/static/css/app.css' />" rel="stylesheet"></link>
		<%-- the "Edges status" block (inc/edges_status.jsp) styles itself from here --%>
		<link href="<c:url value='/static/css/fc-edges-status.css' />" rel="stylesheet"></link>
		<%-- the Configs screens' editor (inc/config_file_editor.jsp) --%>
		<link href="<c:url value='/static/css/fc-config-editor.css' />" rel="stylesheet"></link>
		<%-- the Cache Warmer screen (cache_warmer.jsp) --%>
		<link href="<c:url value='/static/css/fc-warmer.css' />" rel="stylesheet"></link>
		<link rel="stylesheet" type="text/css" href="//cdnjs.cloudflare.com/ajax/libs/font-awesome/4.2.0/css/font-awesome.css" /></head>

	<body>
		<div id="mainWrapper">
		<%@ include file="/WEB-INF/views/inc/menu.jsp"%>
		<div class="container">