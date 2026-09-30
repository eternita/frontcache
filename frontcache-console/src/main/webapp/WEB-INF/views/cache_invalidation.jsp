<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<c:set var="activeCache" value="invalidation"/>
<%@ include file="/WEB-INF/views/inc/cache_menu.jsp"%>

<p/>
<p/>

<form:form method="POST" class="form-inline" modelAttribute="cacheInvalidation">
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
  <button type="submit" name="invalidate" class="btn btn-success">Invalidate cache</button>
  <button type="submit" name="purge" class="btn btn-warning">Purge</button>
  </form:form>

  <c:if test="${not empty edgeError}">
      <div class="alert alert-danger" style="margin-top: 15px;"><c:out value="${edgeError}"/></div>
  </c:if>
  <c:if test="${not empty purgeResult}">
      <div class="alert alert-info" style="margin-top: 15px;"><c:out value="${purgeResult}"/></div>
  </c:if>

    <hr>
    <div class="">
        <div style="padding-left: 20px;">
        <p style="height: 10px">&nbsp;</p>
        <%@ include file="/WEB-INF/views/inc/edges_status.jsp"%>
        </div>
    </div>

<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>