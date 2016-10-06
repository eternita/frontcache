<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="display"%>

<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<html>
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
		<title>Configure Edges - Frontcache Console</title>
	
	
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
		    <div class="">
		        <div style="padding-left: 20px;">
		        <p style="height: 10px">&nbsp;</p>
		        <h2 style="font-size: 22px;">Configure Frontcache Edges</h2>  
		        <br/>
		        
		        <div style="font-size: 18px;"> 
		            <c:forEach var="edge" items="${edges}">
		              <c:out value="${edge.url}"/> - ${edge.onlineStatus}
		              <c:if test="${edge.available}">
		              - <c:out value="${edge.cachedAmountString}"/> items cached  
		              </c:if> 
		              | <a href="remove-edge?edge=${edge.url}">remove</a>
		               <br/>
		            </c:forEach>
		        </div> 
		        
		        <form action="add-edge">
		          Add new edge(s) to console:
                  <input type="text" name="edges" />
                  <input type="submit" value="Add"/> multiple space separated edges are acceptable 
		        </form>         
		        </div>
		    </div>
		    <hr>
		<div>
		To load edges on startup you can
 <p/>		 
		<br/>1. create a file with list of edges.
		E.g. frontcache-console.properties 
		<br/>http://frontcache-1.example.com:8888/
        <br/>http://frontcache-2.example.com:8888/
 
 <p/>
		<br/>2. update startup scripts with Java property 'org.frontcache.console.config' pointed to console config file. For example:
		
		<br/>JAVA_OPTS="-Dorg.frontcache.console.config=/opt/frontcache/conf/frontcache-console.properties"
		
		</div>
			
		
			<hr>
			<footer>
				<p><a href="https://github.com/eternita/frontcache/wiki/Console-UI" target="_blank">Online documentation about Frontcache Console</a></p>
			</footer>
		</div>

		</div>
		</div>
	</body>
</html>