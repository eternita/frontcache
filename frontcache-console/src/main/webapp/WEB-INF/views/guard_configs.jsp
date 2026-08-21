<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<c:set var="activeConfig" value="guard"/>
<%@ include file="/WEB-INF/views/inc/config_menu.jsp"%>

    <hr>

    <!-- one tab per edge; tab order follows the edge order from the console config -->
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${guardConfigs}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab">${edge.key}</a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- start loop over edges - raw ./conf/guard-rules.conf (read-only) -->
      <c:forEach var="edge" items="${guardConfigs}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">
          <p/>
          <textarea class="form-control" readonly="readonly" rows="30"
                    style="width: 100%; font-family: monospace; white-space: pre; overflow: auto;"><c:out value="${edge.value}"/></textarea>
        </div>
      </c:forEach>
      <!-- end loop over edges -->
    </div>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
