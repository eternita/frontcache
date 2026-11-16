<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<c:set var="activeCache" value="view"/>
<%@ include file="/WEB-INF/views/inc/cache_menu.jsp"%>

<p/>
<p/>

<form:form method="POST" class="form-inline" modelAttribute="cacheView">
  <div class="form-group">
    <label for="URL">URL</label>
    <form:input class="form-control" placeholder="http://your.site.com" path="key" style="width: 500px;" />
  </div>   
  <div class="form-group">
    <label for="edge">Edge: </label>
            <form:select class="form-control" path="edge">
               <form:options items="${edgeList}" />
            </form:select>      
  </div>
  <c:if test="${fcPermits['cache.read']}"><button type="submit" name="viewCache" class="btn btn-success">View cache</button></c:if>
  </form:form>

  <c:if test="${not empty edgeError}">
      <div class="alert alert-danger" style="margin-top: 15px;"><c:out value="${edgeError}"/></div>
  </c:if>

  <c:choose>
    <c:when test="${null != webResponse}">

        <table style="width: 100%; margin-top: 20px; margin-bottom: 15px;">
        <tbody>
            <tr>
                   <td style="width: 160px; vertical-align: top;">
                        <b>Expiration date:</b>
                   </td>
                   <td>
                        <%-- one row per visitor type: an unnamed declared type reads its base's stamp, so it is
                             shown as inherited - never as absent, which would read as "not cached" --%>
                        <table class="table table-condensed" style="width: auto; margin-bottom: 5px;">
                          <c:forEach var="row" items="${expiryRows}">
                            <tr>
                              <td><code><c:out value="${row.type}"/></code></td>
                              <td><c:out value="${row.expiry}"/></td>
                              <td>
                                <c:choose>
                                  <c:when test="${not row.known}"><span class="text-muted">not declared on this edge &mdash; never read</span></c:when>
                                  <c:when test="${not empty row.inheritedFrom}"><span class="text-muted">inherited from <c:out value="${row.inheritedFrom}"/></span></c:when>
                                </c:choose>
                                <c:if test="${'expired' == row.state}"> <span class="label label-default">expired</span></c:if>
                                <c:if test="${'not cached' == row.state}"> <span class="label label-default">not cached</span></c:if>
                                <c:if test="${row.noStore}"> <span class="label label-info">no-store</span></c:if>
                              </td>
                            </tr>
                          </c:forEach>
                        </table>
                   </td>
            </tr>
            <tr>
                   <td style="vertical-align: top;">
                        <b>Invalidation tags:</b>
                   </td>
                   <td>
                        <c:forEach var="tag" items="${invalidationTags}">
                            ${tag}&nbsp;&nbsp;
                        </c:forEach>
                   </td>
            </tr>
        </tbody>
        </table>

        <!-- tabs for the fetched result: Headers and Body -->
        <ul class="nav nav-tabs" role="tablist">
            <li role="presentation" class="active"><a href="#tab-headers" data-toggle="tab" role="tab">Headers</a></li>
            <li role="presentation"><a href="#tab-body" data-toggle="tab" role="tab">Body</a></li>
        </ul>

        <div class="tab-content" style="padding-top: 15px;">
            <div role="tabpanel" class="tab-pane active" id="tab-headers">
                <table style="width: 100%;">
                <tbody>
                    <c:forEach var="map" items="${webResponseHeaders}">
                        <tr>
                           <td style="width: 250px; vertical-align: top; padding: 3px 10px 3px 0;">
                                ${map.key}
                           </td>
                           <td style="vertical-align: top; padding: 3px 0; word-break: break-all;">
                            <c:forEach var="item" items="${map.value}">
                                ${item}
                            </c:forEach>
                           </td>
                        </tr>
                    </c:forEach>
                </tbody>
                </table>
            </div>

            <div role="tabpanel" class="tab-pane" id="tab-body">
                <pre style="max-height: 600px; overflow: auto; white-space: pre-wrap; word-wrap: break-word;"><c:out value="${webResponseStr}"/></pre>
            </div>
        </div>

    </c:when>

    <c:otherwise>
        <c:if test="${submitted}">
            <p/><p/>
            <b>Nothing Found</b>
        </c:if>
    </c:otherwise>
  </c:choose>

<p/>
<p/>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>