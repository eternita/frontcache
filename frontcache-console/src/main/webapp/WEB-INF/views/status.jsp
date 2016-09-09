<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="display"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/> 
<p/> 


	<hr>
	<div class="">
		<div style="padding-left: 20px;">
		<p style="height: 10px">&nbsp;</p>
		<h2 style="font-size: 22px;">Edges status</h2>  
		<br/>
		
        <div style="font-size: 18px;"> 
			<c:forEach var="edge" items="${edges}">
			  <c:out value="${edge.name}"/> - ${edge.onlineStatus}
			  <c:if test="${edge.available}">
              - <c:out value="${edge.cachedAmountString}"/> items cached
			  </c:if> 
			   <br/>
			</c:forEach>
        </div> 			
		</div>
	</div>
	<hr>

<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>