<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<p/>
<p/>

<h2 style="font-size: 22px;">Cache Replication</h2>
<p style="color: #666; font-size: 14px;">
    Copy missing cache entries from one edge to another. Entries already present on the target are skipped.
</p>

<form:form method="POST" class="form-inline" modelAttribute="cacheReplication">
  <div class="form-group" style="margin-right: 10px;">
    <label for="sourceEdge">From: </label>
    <form:select class="form-control" path="sourceEdge">
        <option value="">-- select source --</option>
        <form:options items="${edgeList}" />
    </form:select>
  </div>
  <div class="form-group" style="margin-right: 10px;">
    <label for="targetEdge">To: </label>
    <form:select class="form-control" path="targetEdge">
        <option value="">-- select target --</option>
        <form:options items="${edgeList}" />
    </form:select>
  </div>
  <button type="submit" name="startReplication" class="btn btn-success"
      <c:if test="${replicationStatus != null and replicationStatus.status == 'RUNNING'}">disabled</c:if>
  >Start Replication</button>
  <button type="submit" name="cancelReplication" class="btn btn-warning"
      <c:if test="${replicationStatus == null or replicationStatus.status != 'RUNNING'}">disabled</c:if>
  >Cancel</button>
</form:form>

<c:if test="${not empty replicationMessage}">
    <div class="alert alert-info" style="margin-top: 15px;">${replicationMessage}</div>
</c:if>
<c:if test="${not empty replicationError}">
    <div class="alert alert-danger" style="margin-top: 15px;">${replicationError}</div>
</c:if>

<c:if test="${replicationStatus != null}">
    <div style="margin-top: 20px; padding: 15px; background: #f9f9f9; border: 1px solid #ddd; border-radius: 4px;">
        <h3 style="margin-top: 0; font-size: 18px;">
            Status:
            <c:choose>
                <c:when test="${replicationStatus.status == 'RUNNING'}">
                    <span style="color: #337ab7;">Replicating...</span>
                </c:when>
                <c:when test="${replicationStatus.status == 'COMPLETED'}">
                    <span style="color: #5cb85c;">Completed</span>
                </c:when>
                <c:when test="${replicationStatus.status == 'COMPLETED_WITH_ERRORS'}">
                    <span style="color: #f0ad4e;">Completed with errors</span>
                </c:when>
                <c:when test="${replicationStatus.status == 'CANCELLED'}">
                    <span style="color: #777;">Cancelled</span>
                </c:when>
                <c:when test="${replicationStatus.status == 'FAILED'}">
                    <span style="color: #d9534f;">Failed</span>
                </c:when>
                <c:otherwise>
                    <span>${replicationStatus.status}</span>
                </c:otherwise>
            </c:choose>
        </h3>

        <c:if test="${replicationStatus.progressFraction >= 0}">
            <div class="progress" style="height: 25px; margin-bottom: 15px;">
                <div class="progress-bar progress-bar-striped active"
                     role="progressbar"
                     style="width: ${replicationStatus.progressFraction * 100}%; min-width: 2em;"
                     aria-valuenow="${replicationStatus.progressFraction * 100}"
                     aria-valuemin="0" aria-valuemax="100">
                    <c:out value="${Math.round(replicationStatus.progressFraction * 100)}"/>%
                </div>
            </div>
        </c:if>

        <table class="table table-striped" style="margin-bottom: 0;">
            <tr><td style="width: 200px;"><strong>Source</strong></td><td>${replicationStatus.sourceUrl}</td></tr>
            <tr><td><strong>Target</strong></td><td>${replicationStatus.targetUrl}</td></tr>
            <tr><td><strong>Total source keys</strong></td><td>${replicationStatus.totalSourceKeys}</td></tr>
            <tr><td><strong>Transferred</strong></td><td style="color: #5cb85c;">${replicationStatus.transferred}</td></tr>
            <tr><td><strong>Skipped</strong></td><td style="color: #777;">${replicationStatus.skipped}</td></tr>
            <tr><td><strong>Errors</strong></td><td style="color: #d9534f;">${replicationStatus.errors}</td></tr>
            <tr>
                <td><strong>Remaining</strong></td>
                <td>
                    <c:choose>
                        <c:when test="${replicationStatus.remaining >= 0}">${replicationStatus.remaining}</c:when>
                        <c:otherwise>unknown</c:otherwise>
                    </c:choose>
                </td>
            </tr>
            <tr><td><strong>Elapsed</strong></td><td>${replicationStatus.durationMillis} ms</td></tr>
        </table>
    </div>
</c:if>

<p/>

<div class="">
    <div style="padding-left: 20px;">
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
