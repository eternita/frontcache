<%@ taglib uri="jakarta.tags.core" prefix="display"%>

<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<html>
	<head>
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
		<title>Configure Edges - Frontcache Console</title>
	
    <link rel="shortcut icon" href="static/images/favicon.ico" type="image/x-icon"/> 
	
    <script src="<c:url value='https://ajax.googleapis.com/ajax/libs/jquery/2.2.2/jquery.min.js' />"></script>
	<script src="<c:url value='/static/js/bootstrap.js' />"></script>
	<link href="<c:url value='/static/css/app.css' />" rel="stylesheet"></link>
	<link href="<c:url value='/static/css/bootstrap.css' />" rel="stylesheet"></link>
	<link href="<c:url value='/static/css/fc-edges-status.css' />" rel="stylesheet"></link>
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
		        
        <%-- the same live block as the rest of the console, plus this page's remove button. The dot
             and the count are placeholders filled by fc-edges-status.js from /edges-status.json, so
             this page paints without waiting on the fleet and its numbers do not go stale while it
             is open. Not inc/edges_status.jsp itself only because of the extra column. --%>
        <div id="fc-edges-status" class="fc-edges-status fc-edges-status-wide" style="font-size: 18px;">
        
             <table >
                <c:forEach var="edge" items="${edges}">
                  <tr data-edge-url="<c:out value='${edge.url}'/>">
                     <td align="left" style="padding: 5px; vertical-align: middle;">
                         <span class="fc-dot fc-dot-pending" data-field="dot" title="checking ..."></span>
                     </td>
                     <td align="left" style="padding: 5px;">
                        <c:out value="${edge.name}"/>
                     </td>
                     <td align="left" style="padding-left: 20px;">
                        <span data-field="cachedEntries">&mdash;</span> items cached
                        <span class="fc-edge-offline" data-field="offline" style="display: none">not reachable</span>
                     </td>
                     <td align="left" style="padding: 5px;">
                      <%-- a POST, not a link: removing an edge changes console state, and a GET that
                           does so can be triggered from any page the operator has open - the browser
                           attaches the console's Basic credentials by itself. Styled as the button it
                           already looked like. --%>
                      <form action="remove-edge" method="post" style="margin: 0;">
                        <input type="hidden" name="edge" value="<c:out value='${edge.url}'/>"/>
                        <button type="submit" class="btn btn-warning">remove</button>
                      </form>
                     </td>
                 </tr>
                </c:forEach>
             </table>

             <script src="<c:url value='/static/js/fc-edges-status.js' />"></script>
             <script>
                FcEdgesStatus.attach(document.getElementById("fc-edges-status"), "edges-status.json");
             </script>
		        
		        </div>

                <form class="form-inline" action="add-edge" method="post">
                  <div class="form-group" style="padding-top: 25px;">
                     <label for="edges"> Add new edge(s) to console: </label> multiple space separated edges are acceptable<br/>
                     <input type="text" name="edges" class="form-control"  size="100"/>
                     <input  type="submit" class="btn btn-success" style="vertical-align:bottom;" value="Add"/>  
                  </div>
                </form>         

		    </div>
		    <hr>
		</div>

		</div>
		</div>
	</body>
</html>