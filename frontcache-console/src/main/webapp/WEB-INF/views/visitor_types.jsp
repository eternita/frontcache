<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

    <p class="text-muted">
      How each request is classified as <code>bot</code> or <code>guest</code>, in evaluation order
      &mdash; first match wins. The built-in <code>no-user-agent</code> comes first and the built-in
      <code>default</code> (which always matches) comes last, with the rules and the bare User-Agent
      keywords of <code>FRONTCACHE_HOME/conf/bots.conf</code> in between &mdash; the file itself is
      under <a href="bot-configs">Configs / Bot Configs</a>.
      The client type is not a verdict about the client: it selects a behaviour profile &mdash; which
      branch of a cached response's max-age applies, so a different TTL or an origin call instead of
      the cache, and whether an <code>&lt;fc:include client="..."&gt;</code> fragment is resolved.
      <b>Hits</b> counts the requests each rule <i>decided</i> since the node started (or since the
      last <code>reload-bots</code>); a rule with no hits is doing nothing, and the count on
      <code>default</code> is the traffic no rule claimed.
    </p>
    <p class="text-muted">
      Beside the two built-in types, <code>bots.conf</code> may declare further visitor types
      (<code>@type customer like guest</code>). A declared type <b>behaves like</b> its base everywhere
      nothing names it &mdash; in every cached entry, every page's <code>maxage</code>, every
      <code>client=</code> and every guard rule &mdash; and only where a page or rule names it does it
      differ. <b>no-store</b>: its requests are served cached copies but never write one.
    </p>

    <%-- one tab per edge, keyed by its URL (two edges on one host share a name); the order is the console config's --%>
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${visitorTypes}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab"
             title="<c:out value='${edge.key}'/>"><c:out value="${edge.value.name}"/></a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- one panel per edge - Map <edge URL, VisitorTypesConfig> -->
      <c:forEach var="edge" items="${visitorTypes}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">

          <%-- 2.13.0+ edge: the types it knows - one flat list with a behaves-like column, never a tree --%>
          <c:if test="${not empty edge.value.types}">
            <table class="table table-condensed fc-visitor-types">
              <thead>
                <tr>
                  <th>Visitor type</th>
                  <th>Behaves like</th>
                  <th>Cache writes</th>
                  <th class="text-right">Hits</th>
                </tr>
              </thead>
              <tbody>
              <c:forEach var="type" items="${edge.value.types}">
                <tr>
                  <td><code><c:out value="${type.name}"/></code></td>
                  <td>
                    <c:choose>
                      <c:when test="${type.builtIn}"><span class="text-muted">built-in</span></c:when>
                      <c:otherwise><code><c:out value="${type.base}"/></code></c:otherwise>
                    </c:choose>
                  </td>
                  <td>
                    <c:choose>
                      <c:when test="${type.noStore}"><span class="label label-info">no-store</span></c:when>
                      <c:otherwise><span class="text-muted">as its pages say</span></c:otherwise>
                    </c:choose>
                  </td>
                  <td class="text-right">${type.hits}</td>
                </tr>
              </c:forEach>
              </tbody>
            </table>
          </c:if>

          <c:choose>

            <%-- 2.9.0+ edge: the ordered rules, with hit counts --%>
            <c:when test="${not empty edge.value.rules}">
              <table class="table table-striped">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Rule</th>
                    <th>Condition</th>
                    <th>Visitor type</th>
                    <th>Mode</th>
                    <th class="text-right">Hits</th>
                  </tr>
                </thead>
                <tbody>

                <c:forEach var="rule" items="${edge.value.rules}" varStatus="ruleStatus">
                  <tr>
                    <td>${ruleStatus.index + 1}</td>
                    <td><b>${rule.name}</b></td>
                    <td><code>${rule.condition}</code></td>
                    <td><code>${rule.clientType}</code></td>
                    <td>
                      <c:choose>
                        <c:when test="${rule.dryRun}"><span class="label label-warning">dry-run</span></c:when>
                        <c:otherwise><span class="label label-success">active</span></c:otherwise>
                      </c:choose>
                    </td>
                    <td class="text-right">${rule.hits}</td>
                  </tr>
                </c:forEach>

                </tbody>
              </table>
            </c:when>

            <%-- edge older than 2.9.0: it answers with bare keywords and no rules --%>
            <c:otherwise>
              <p class="text-muted">
                <i>This edge predates bot classification rules &mdash; it reports User-Agent keywords only.</i>
              </p>
              <table class="table table-striped">
                <thead>
                  <tr>
                    <th><b>Bot pattern (for User-Agent header)</b></th>
                  </tr>
                </thead>
                <tbody>
                <c:forEach var="bot" items="${edge.value.config}">
                  <tr>
                    <td>${bot}  &nbsp;</td>
                  </tr>
                </c:forEach>
                <c:if test="${empty edge.value.config}">
                  <tr><td><i>no bot configuration loaded</i></td></tr>
                </c:if>
                </tbody>
              </table>
            </c:otherwise>

          </c:choose>

        </div>
      </c:forEach>
      <!-- end loop over edges -->
    </div>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
