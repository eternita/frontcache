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
  <button type="submit" name="viewCache" class="btn btn-success">View cache</button>
  </form:form>

  <c:choose>
    <c:when test="${null != webResponse}">

        <table style="width: 100%; margin-top: 20px; margin-bottom: 15px;">
        <tbody>
            <tr>
                   <td style="width: 160px; vertical-align: top;">
                        <b>Expiration date:</b>
                   </td>
                   <td>
                        ${expirationDateStr}
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