<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ taglib prefix="fn" uri="jakarta.tags.functions"%>
<%
	// plugin names, reasons and config errors are the node's text, and may be anyone's language
	response.setCharacterEncoding("UTF-8");
%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>
<%-- The Plugins screen (PluginsController): per edge of the scope, every plugin its get-node-stats reports.

     Expects in the model:
       ${pluginEdges}           - List<EdgePlugins>: name, url, available, reported, plugins (List<PluginView>)
       ${configEditingEnabled}  - false hides Reload config (no credentials, or config-editing = false);
                                  narrowed below to a principal holding config.write (fcPermits)
       ${reloadResult}          - ReloadPluginConfigActionResponse after a POST, else absent
       ${reloadEdgeURL}         - the edge it was sent to
       ${activeEdgeURL}         - which tab to open on after a POST

     Everything a node reported is rendered through c:out: a plugin's name, vendor, reason and config errors are
     whatever a jar dropped into a plugin directory says. --%>

<style>
  .fc-plugin .panel-heading .label { margin-left: 4px; }
  .fc-plugin dl { margin-bottom: 0; }
  .fc-plugin dt { width: 170px; }
  .fc-plugin dd { margin-left: 190px; }
  .fc-plugin .fc-over-budget { font-size: 100%; }
  .fc-plugin .fc-reload { display: inline; margin-left: 8px; }
</style>

<%-- the console's switch AND the principal's permission; /plugins-reload refuses it either way --%>
<c:set var="reloadEnabled" value="${configEditingEnabled and fcPermits['config.write']}"/>

<p class="text-muted">
  The plugins each node found &mdash; bundled with it, or dropped into its plugin directory &mdash; what each
  declares, its state, the last read of its config and its counters, as the node's <code>get-node-stats</code>
  reports them. Counters are the process's: a reload does not reset them.
  A <span class="label label-danger">request path</span> plugin runs code on requests;
  <span class="label label-danger">over budget</span> means its predicates were switched off and its rules do not
  match until it is tried again.
</p>

<c:if test="${not empty reloadResult}">
  <div class="alert ${reloadResult.responseStatus == 'ERROR' or not reloadResult.applied ? 'alert-danger' : 'alert-success'}"
       style="margin-bottom: 10px;">
    <strong><c:out value="${reloadEdgeURL}"/></strong>
    &mdash; plugin <code><c:out value="${reloadResult.plugin}"/></code>:
    <c:choose>
      <c:when test="${reloadResult.applied}">config reloaded<c:if test="${not empty reloadResult.state}">, now
        <c:out value="${reloadResult.state}"/></c:if></c:when>
      <c:otherwise>config NOT reloaded<c:if test="${not empty reloadResult.errorDescription}"> &mdash;
        <c:out value="${reloadResult.errorDescription}"/></c:if></c:otherwise>
    </c:choose>
    <c:if test="${not empty reloadResult.errors}">
      <ul style="margin-top: 8px; margin-bottom: 0;">
        <c:forEach var="err" items="${reloadResult.errors}"><li><code><c:out value="${err}"/></code></li></c:forEach>
      </ul>
    </c:if>
  </div>
</c:if>

<%-- one tab per edge, keyed by its URL (two edges on one host share a name); the order is the console config's --%>
<ul class="nav nav-tabs" role="tablist">
  <c:forEach var="edge" items="${pluginEdges}" varStatus="status">
    <c:set var="isActive" value="${(empty activeEdgeURL and status.first) or (edge.url == activeEdgeURL)}"/>
    <li role="presentation" class="${isActive ? 'active' : ''}">
      <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab" title="<c:out value='${edge.url}'/>"><c:out
         value="${edge.name}"/> <span class="badge"><c:out value="${fn:length(edge.plugins)}"/></span><c:if
         test="${not edge.available}"> <span class="fc-edge-offline">(not reachable)</span></c:if></a>
    </li>
  </c:forEach>
</ul>

<div class="tab-content">
  <c:forEach var="edge" items="${pluginEdges}" varStatus="status">
    <c:set var="isActive" value="${(empty activeEdgeURL and status.first) or (edge.url == activeEdgeURL)}"/>
    <div role="tabpanel" class="tab-pane ${isActive ? 'active' : ''}" id="edge-tab-${status.index}"
         data-edge-url="<c:out value='${edge.url}'/>">
      <p/>
      <c:choose>
        <c:when test="${not edge.available}">
          <div class="alert alert-warning"><c:out value="${edge.url}"/> did not answer.</div>
        </c:when>
        <c:when test="${not edge.reported}">
          <div class="alert alert-info">No plugin information: this node predates <code>get-node-stats</code>.</div>
        </c:when>
        <c:when test="${empty edge.plugins}">
          <div class="alert alert-info">No plugin information: this node runs no plugin, or predates plugins.</div>
        </c:when>
        <c:otherwise>
          <c:forEach var="p" items="${edge.plugins}">
            <c:set var="s" value="${p.stats}"/>
            <c:choose>
              <c:when test="${p.overBudget or s.state == 'failed' or s.state == 'refused'}"><c:set var="panelClass" value="panel-danger"/></c:when>
              <c:when test="${s.state == 'started'}"><c:set var="panelClass" value="panel-default"/></c:when>
              <c:otherwise><c:set var="panelClass" value="panel-warning"/></c:otherwise>
            </c:choose>
            <div class="panel ${panelClass} fc-plugin" data-plugin="<c:out value='${s.id}'/>">
              <div class="panel-heading">
                <strong><c:out value="${s.id}"/></strong>
                <c:if test="${not empty s.name and s.name != s.id}"> &mdash; <c:out value="${s.name}"/></c:if>
                <c:if test="${not empty s.version}"> <span class="text-muted"><c:out value="${s.version}"/></span></c:if>
                <c:choose>
                  <c:when test="${s.state == 'started'}"><span class="label label-success fc-state"><c:out value="${s.state}"/></span></c:when>
                  <c:when test="${s.state == 'failed' or s.state == 'refused'}"><span class="label label-danger fc-state"><c:out value="${s.state}"/></span></c:when>
                  <c:otherwise><span class="label label-default fc-state"><c:out value="${s.state}"/></span></c:otherwise>
                </c:choose>
                <span class="label label-info"><c:out value="${s.source}"/></span>
                <c:forEach var="kind" items="${s.kinds}"><span class="label label-primary"><c:out value="${kind}"/></span></c:forEach>
                <c:if test="${s.requestPath}"><span class="label label-danger fc-request-path">request path</span></c:if>
                <c:if test="${s.legacy}"><span class="label label-default">legacy module</span></c:if>
                <c:if test="${p.overBudget}"><span class="label label-danger fc-over-budget">over budget</span></c:if>
              </div>
              <div class="panel-body">
                <c:if test="${p.overBudget}">
                  <div class="alert alert-danger">
                    <strong>Switched off:</strong> its predicates do not answer, so the rules naming them do not match.
                    <c:if test="${not empty s.decideReason}"><c:out value="${s.decideReason}"/>.</c:if>
                    <c:if test="${not empty p.decideRetryAtText}">Tried again at <c:out value="${p.decideRetryAtText}"/>.</c:if>
                  </div>
                </c:if>
                <dl class="dl-horizontal">
                  <c:if test="${not empty s.reason}">
                    <dt>Reason</dt><dd class="text-danger"><c:out value="${s.reason}"/></dd>
                  </c:if>
                  <dt>Vendor</dt><dd><c:out value="${empty s.vendor ? '-' : s.vendor}"/></dd>
                  <dt>License</dt><dd><c:out value="${empty s.license ? '-' : s.license}"/></dd>
                  <dt>Plugin API</dt><dd><c:out value="${s.pluginApi}"/></dd>
                  <dt>Source</dt>
                  <dd>
                    <c:out value="${s.source}"/>
                    <c:if test="${not empty s.directory}"> &mdash; <code><c:out value="${s.directory}"/></code></c:if>
                    <c:if test="${s.source == 'dropped-in'}">
                      <c:choose>
                        <c:when test="${s.pinned}"><span class="label label-success">pinned</span></c:when>
                        <c:otherwise><span class="label label-default">not pinned</span></c:otherwise>
                      </c:choose>
                    </c:if>
                    <c:if test="${not empty s.jars}">
                      <ul class="list-unstyled" style="margin: 4px 0 0 0;">
                        <c:forEach var="jar" items="${s.jars}"><li><small><code><c:out value="${jar}"/></code></small></li></c:forEach>
                      </ul>
                    </c:if>
                  </dd>

                  <dt>Config</dt>
                  <dd>
                    <c:choose>
                      <c:when test="${empty s.config}"><span class="text-muted">none</span></c:when>
                      <c:otherwise>
                        <code>conf/<c:out value="${s.config}"/></code>
                        <c:choose>
                          <c:when test="${s.configStatus == 'ok'}"><span class="label label-success fc-config-status">ok</span></c:when>
                          <c:when test="${s.configStatus == 'refused'}"><span class="label label-danger fc-config-status">refused</span></c:when>
                          <c:when test="${s.configStatus == 'absent'}"><span class="label label-default fc-config-status">absent</span></c:when>
                          <c:when test="${not empty s.configStatus}"><span class="label label-default fc-config-status"><c:out value="${s.configStatus}"/></span></c:when>
                          <c:otherwise><span class="text-muted">not read yet</span></c:otherwise>
                        </c:choose>
                        <c:if test="${not empty p.configLoadedText}"><small class="text-muted">accepted <c:out value="${p.configLoadedText}"/></small></c:if>
                        <c:if test="${reloadEnabled and p.reloadable}">
                          <form method="POST" action="plugins-reload" class="fc-reload">
                            <input type="hidden" name="edge" value="<c:out value='${edge.url}'/>"/>
                            <input type="hidden" name="plugin" value="<c:out value='${s.id}'/>"/>
                            <button type="submit" class="btn btn-default btn-xs">Reload config</button>
                          </form>
                        </c:if>
                        <c:if test="${not empty s.configErrors}">
                          <ul style="margin: 4px 0 0 0;">
                            <c:forEach var="err" items="${s.configErrors}"><li><code><c:out value="${err}"/></code></li></c:forEach>
                          </ul>
                        </c:if>
                      </c:otherwise>
                    </c:choose>
                  </dd>

                  <dt>Events</dt>
                  <dd><c:out value="${s.eventsDelivered}"/> delivered, <c:out value="${s.eventsDropped}"/> dropped,
                    <c:out value="${s.errors}"/> errors</dd>

                  <c:if test="${p.serving}">
                    <dt>Serve</dt>
                    <dd>
                      <c:choose>
                        <c:when test="${s.serves}">answers <code>/fc-x/<c:out value="${s.id}"/>/</code></c:when>
                        <c:otherwise><span class="text-muted">not answering</span></c:otherwise>
                      </c:choose>
                      &mdash; <c:out value="${s.served}"/> served, <c:out value="${s.serveRefused}"/> refused,
                      <c:out value="${s.serveFailed}"/> failed
                    </dd>
                    <c:if test="${not empty s.cookies}">
                      <dt>Cookies</dt>
                      <dd><c:forEach var="ck" items="${s.cookies}" varStatus="cs"><code><c:out value="${ck}"/></code><c:if test="${not cs.last}">, </c:if></c:forEach></dd>
                    </c:if>
                    <c:if test="${not empty s.challenges}">
                      <dt>Challenges</dt>
                      <dd><c:forEach var="ch" items="${s.challenges}" varStatus="chs"><code><c:out value="${ch}"/></code><c:if test="${not chs.last}">, </c:if></c:forEach></dd>
                    </c:if>
                  </c:if>

                  <c:if test="${p.decides}">
                    <dt>Decide</dt>
                    <dd>
                      <c:choose>
                        <c:when test="${p.overBudget}"><span class="label label-danger fc-decide-state">over budget</span></c:when>
                        <c:when test="${s.decideState == 'ok'}"><span class="label label-success fc-decide-state">ok</span></c:when>
                        <c:when test="${not empty s.decideState}"><span class="label label-default fc-decide-state"><c:out value="${s.decideState}"/></span></c:when>
                        <c:otherwise><span class="text-muted">not started</span></c:otherwise>
                      </c:choose>
                      p99 <c:choose><c:when test="${empty p.decideP99Text}"><span class="text-muted">not yet judged</span></c:when><c:otherwise><c:out
                        value="${p.decideP99Text}"/>&nbsp;&micro;s</c:otherwise></c:choose>
                      of a <c:out value="${s.decideBudgetMicros}"/>&nbsp;&micro;s budget &mdash;
                      <c:out value="${s.decideCalls}"/> calls, <c:out value="${s.decideErrors}"/> errors,
                      switched off <c:out value="${s.decideSwitchedOff}"/> times
                      <c:if test="${not p.overBudget and not empty s.decideReason}">
                        <br/><small class="text-muted">last switched off: <c:out value="${s.decideReason}"/></small>
                      </c:if>
                    </dd>
                    <c:if test="${not empty s.predicates}">
                      <dt>Predicates</dt>
                      <dd><c:forEach var="pr" items="${s.predicates}" varStatus="prs"><code>ext:<c:out value="${pr}"/>:</code><c:if test="${not prs.last}">, </c:if></c:forEach></dd>
                    </c:if>
                  </c:if>
                </dl>
              </div>
            </div>
          </c:forEach>
        </c:otherwise>
      </c:choose>
    </div>
  </c:forEach>
</div>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
