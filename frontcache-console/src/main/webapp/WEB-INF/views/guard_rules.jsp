<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

    <p class="text-muted">
      Checks that run <b>before cache and origin</b>, in evaluation order &mdash; first match wins.
      The two built-ins (<code>uri-too-long</code>, <code>bad-request</code>) come first, then the
      rules from <code>FRONTCACHE_HOME/conf/guard-rules.conf</code>.
      <b>Hits</b> counts the requests each rule matched since the node started (or since the last
      reload); a rule with no hits is doing nothing.
    </p>

    <!-- one tab per edge; tab order follows the edge order from the console config -->
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${guardRules}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab">${edge.key}</a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- start loop over edges -->
      <c:forEach var="edge" items="${guardRules}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">

          <table class="table table-striped">
          <thead>
            <tr>
              <th>#</th>
              <th>Rule</th>
              <th>Condition</th>
              <th>Action</th>
              <th>Mode</th>
              <th class="text-right">Hits</th>
            </tr>
          </thead>
          <tbody>

          <c:forEach var="rule" items="${edge.value}" varStatus="ruleStatus">
            <tr>
              <td>${ruleStatus.index + 1}</td>
              <td><b>${rule.name}</b></td>
              <td><code>${rule.condition}</code></td>
              <td><code>${rule.action}</code></td>
              <td>
                <c:choose>
                  <c:when test="${rule.dryRun}"><span class="label label-warning">dry-run</span></c:when>
                  <c:otherwise><span class="label label-success">active</span></c:otherwise>
                </c:choose>
              </td>
              <td class="text-right">${rule.hits}</td>
            </tr>
          </c:forEach>

          <c:if test="${empty edge.value}">
            <tr><td colspan="6"><i>no guard rules loaded</i></td></tr>
          </c:if>

          </tbody>
        </table>

        </div>
      </c:forEach>
      <!-- end loop over edges -->
    </div>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
