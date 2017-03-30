<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="display"%>

<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<html>
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
		<title>Configure Edges - Frontcache Console</title>
	
    <link rel="shortcut icon" href="static/images/favicon.ico" type="image/x-icon"/> 
	
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
        
             <table >
                <c:forEach var="edge" items="${edges}">
                  <tr>
                     <td align="left" style="padding: 5px; vertical-align: middle;">
                         <c:choose>
                          <c:when test="${'ONLINE' == edge.onlineStatus}">
                            <img alt="status" style="" src="static/images/status/green.png" width="15px">
                          </c:when>
                          <c:otherwise>
                            <img alt="status" style="" src="static/images/status/red.png" width="15px">
                          </c:otherwise> 
                         </c:choose>
                     </td>
                     <td align="left" style="padding: 5px;">
                        <c:out value="${edge.name}"/>
                     </td>
                     <td align="left" style="padding-left: 20px;">
                         <c:choose>
                          <c:when test="${'ONLINE' == edge.onlineStatus}">
                            <c:out value="${edge.cachedAmountString}"/> items cached
                          </c:when>
                          <c:otherwise>
                            &nbsp;
                          </c:otherwise> 
                         </c:choose>
                     </td>
                     <td align="left" style="padding: 5px;">
                      <a class="btn btn-warning" href="remove-edge?edge=${edge.url}">remove</a>
                     </td>
                 </tr>
                </c:forEach>
             </table>
		        
		        </div>

                <form class="form-inline" action="add-edge">
                  <div class="form-group" style="padding-top: 25px;">
                     <label for="edges"> Add new edge(s) to console: </label> multiple space separated edges are acceptable<br/>
                     <input type="text" name="edges" class="form-control"  size="100"/>
                     <input  type="submit" class="btn btn-success" style="vertical-align:bottom;" value="Add"/>  
                  </div>
                </form>         

		    </div>
		    <hr>
		<div>
		<b>Steps to load edges on startup:</b>
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