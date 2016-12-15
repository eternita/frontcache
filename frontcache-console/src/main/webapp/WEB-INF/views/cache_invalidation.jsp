<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<p/> 
<p/> 

<form:form method="POST" class="form-inline" commandName="cacheInvalidation">
  <div class="form-group">
    <label for="URL">URL / tag</label>
    <form:input class="form-control" placeholder="URL  |  invalidation tag  |  * to remove all" path="filter" style="width: 500px;" />
  </div>   
  <div class="form-group">
    <label for="edge">Edge: </label>
            <form:select class="form-control" path="edge">
               <form:options items="${edgeList}" />
            </form:select>      
  </div>
  <button type="submit" class="btn btn-success">Invalidate cache</button>
  </form:form>
  
    <div class="">
        <div style="padding-left: 20px;">
        <p style="height: 10px">&nbsp;</p>
        <h2 style="font-size: 22px;">Edges status</h2>  
        <br/>
        
    <hr>
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

<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>