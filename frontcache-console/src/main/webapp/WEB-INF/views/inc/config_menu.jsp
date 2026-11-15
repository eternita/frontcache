<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- sub menu to switch between config screens; ${activeConfig} marks the current one -->
<ul class="nav nav-pills" role="tablist">
  <li role="presentation" class="${activeConfig == 'fallbacks' ? 'active' : ''}"><a href="fallbacks">Fallback Config</a></li>
  <li role="presentation" class="${activeConfig == 'bot' ? 'active' : ''}"><a href="bot-configs">Bot Configs</a></li>
  <li role="presentation" class="${activeConfig == 'dynamic' ? 'active' : ''}"><a href="dynamic-urls-configs">Dynamic URLs Config</a></li>
  <li role="presentation" class="${activeConfig == 'resilience' ? 'active' : ''}"><a href="resilience-configs">Resilience Configs</a></li>
  <li role="presentation" class="${activeConfig == 'guard' ? 'active' : ''}"><a href="guard-configs">Guard Configs</a></li>
  <li role="presentation" class="${activeConfig == 'plugin' ? 'active' : ''}"><a href="plugin-configs">Plugin Configs</a></li>
</ul>
<c:if test="${activeConfig == 'plugin'}">
  <%-- the plugins with a config that an edge of this scope runs (get-node-stats), one pill each --%>
  <c:choose>
    <c:when test="${empty pluginConfigs}">
      <p/>
      <div class="alert alert-info">No edge here runs a plugin with a config file.</div>
    </c:when>
    <c:otherwise>
      <p/>
      <ul class="nav nav-pills" role="tablist">
        <c:forEach var="pc" items="${pluginConfigs}">
          <li role="presentation" class="${pc.key == activePlugin ? 'active' : ''}"><a
              href="plugin-configs?plugin=<c:out value='${pc.key}'/>"><c:out value="${pc.key}"/>
              <small>(<c:out value="${pc.value}"/>)</small></a></li>
        </c:forEach>
      </ul>
    </c:otherwise>
  </c:choose>
</c:if>
