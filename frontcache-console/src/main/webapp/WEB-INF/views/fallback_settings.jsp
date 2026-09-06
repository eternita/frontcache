<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<c:set var="activeConfig" value="fallbacks"/>
<%@ include file="/WEB-INF/views/inc/config_menu.jsp"%>

    <hr>

    <p class="text-muted">
      <code>FRONTCACHE_HOME/conf/fallbacks.conf</code> as it sits on disk, comments and all &mdash;
      what each entry serves when the origin is unreachable. One record per line: the URL pattern, the
      file in the <code>fallbacks</code> directory that answers it, and optionally the URL that file is
      populated from the first time it is needed.
      Read-only: edit the file and apply it with the <code>reload-fallbacks</code> action.
    </p>

    <!-- one tab per edge; tab order follows the edge order from the console config -->
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${fallbackConfigs}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab">${edge.key}</a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- start loop over edges - raw ./conf/fallbacks.conf (read-only) -->
      <c:forEach var="edge" items="${fallbackConfigs}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">
          <p/>
          <c:choose>
            <%-- the file is optional, and an edge older than 2.11 does not know the action at all --%>
            <c:when test="${empty edge.value}">
              <p class="text-muted"><i>no fallbacks.conf on this edge &mdash; an unreachable origin
              returns an error rather than stored content</i></p>
            </c:when>
            <c:otherwise>
              <textarea class="form-control" readonly="readonly" rows="30"
                        style="width: 100%; font-family: monospace; white-space: pre; overflow: auto;"><c:out value="${edge.value}"/></textarea>
            </c:otherwise>
          </c:choose>
        </div>
      </c:forEach>
      <!-- end loop over edges -->
    </div>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
