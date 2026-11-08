<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<c:set var="activeCache" value="other"/>
<%@ include file="/WEB-INF/views/inc/cache_menu.jsp"%>

<p/>
<p/>

<form:form method="POST" class="form-inline" modelAttribute="cacheOther">
  <div class="form-group">
    <label for="edge">Edge: </label>
            <form:select class="form-control" path="edge">
               <form:options items="${edgeList}" />
            </form:select>
  </div>
  <%-- offered to who may use them (fcPermits); the handlers refuse the rest either way --%>
  <c:if test="${fcPermits['cache.read']}"><button type="submit" name="dumpKeys" class="btn btn-success">Dump keys</button></c:if>
  <c:if test="${fcPermits['cache.invalidate']}"><button type="submit" name="purge" class="btn btn-warning">Purge</button></c:if>
  <c:if test="${fcPermits['cache.replicate']}"><button type="submit" name="backupIndex" class="btn btn-success">Backup index</button></c:if>
  </form:form>

  <c:if test="${not empty edgeError}">
      <div class="alert alert-danger" style="margin-top: 15px;"><c:out value="${edgeError}"/></div>
  </c:if>
  <c:if test="${not empty dumpKeysResult}">
      <div class="alert alert-info" style="margin-top: 15px;"><c:out value="${dumpKeysResult}"/></div>
  </c:if>
  <c:if test="${not empty purgeResult}">
      <div class="alert alert-info" style="margin-top: 15px;"><c:out value="${purgeResult}"/></div>
  </c:if>
  <c:if test="${not empty backupResult}">
      <div class="alert alert-info" style="margin-top: 15px;"><c:out value="${backupResult}"/></div>
  </c:if>

<p/>
<p/>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
