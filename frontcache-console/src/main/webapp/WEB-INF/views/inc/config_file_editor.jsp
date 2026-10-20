<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%@ taglib prefix="fn" uri="jakarta.tags.functions"%>
<!-- One editor for all five Configs screens - conf/resilience.properties, guard-rules.conf,
     bots.conf, dynamic-urls.conf and fallbacks.conf. Before this the five JSPs were byte-for-byte
     identical apart from the model key, which meant five copies of any change made here; the fifth
     is the one that would have been missed.

     Expects in the model:
       ${configFileId}          - the logical id sent to the node (bots, guard-rules, ...)
       ${configFileName}        - the file's name on disk, shown to the operator
       ${configEdges}           - List<EdgeConfigFile>: name, url, content, available
       ${configEditingEnabled}  - false hides Edit entirely (no credentials, or config-editing=false)
       ${saveResults}           - Map<edge url, SetConfigActionResponse> after a POST, else absent
       ${activeEdgeURL}         - which tab to open on after a POST

     A tab is identified by the edge URL and NOT by its name: that name has the port stripped off, so
     two edges on one host collapse into one - which is the e2e topology, and with a Save button on
     the tab it would send an edit to the wrong node. -->

<c:set var="tabPrefix" value="edge-tab-"/>

<hr>

<c:if test="${not empty saveResults}">
  <!-- One line per node written. Deliberately per node and never a single summary: each node is a
       separate machine, so a partial result is the normal outcome of "Save to all nodes". -->
  <div class="fc-config-results">
    <c:forEach var="result" items="${saveResults}">
      <c:set var="r" value="${result.value}"/>
      <c:choose>
        <c:when test="${r.responseStatus == 'ERROR'}"><c:set var="alertClass" value="alert-danger"/></c:when>
        <c:when test="${not empty r.warnings}"><c:set var="alertClass" value="alert-warning"/></c:when>
        <c:otherwise><c:set var="alertClass" value="alert-success"/></c:otherwise>
      </c:choose>

      <div class="alert ${alertClass}" style="margin-bottom: 10px;">
        <strong><c:out value="${result.key}"/></strong>
        &mdash;
        <c:choose>
          <c:when test="${r.responseStatus == 'ERROR'}"><c:out value="${r.errorDescription}"/></c:when>
          <c:when test="${r.written and r.applied}">saved and applied</c:when>
          <c:when test="${r.written}">saved, NOT applied</c:when>
          <c:otherwise>nothing written</c:otherwise>
        </c:choose>

        <c:if test="${not empty r.backupFile}">
          <br/><small>previous content kept as <code><c:out value="${r.backupFile}"/></code></small>
        </c:if>

        <c:if test="${not empty r.validationErrors}">
          <ul style="margin-top: 8px; margin-bottom: 0;">
            <c:forEach var="err" items="${r.validationErrors}"><li><code><c:out value="${err}"/></code></li></c:forEach>
          </ul>
        </c:if>

        <c:if test="${not empty r.warnings}">
          <ul style="margin-top: 8px; margin-bottom: 0;">
            <c:forEach var="warn" items="${r.warnings}"><li><small><c:out value="${warn}"/></small></li></c:forEach>
          </ul>
        </c:if>
      </div>
    </c:forEach>
  </div>
</c:if>

<div class="fc-config-header">
  <!-- one tab per edge; tab order follows the edge order from the console config -->
  <ul class="nav nav-tabs" role="tablist">
    <c:forEach var="edge" items="${configEdges}" varStatus="status">
      <c:set var="isActive" value="${(empty activeEdgeURL and status.first) or (edge.url == activeEdgeURL)}"/>
      <li role="presentation" class="${isActive ? 'active' : ''}">
        <a href="#${tabPrefix}${status.index}" role="tab" data-toggle="tab"
           data-edge-url="<c:out value='${edge.url}'/>"><c:out value="${edge.name}"/><c:if
           test="${not edge.available}"> <span class="fc-edge-offline">(not reachable)</span></c:if></a>
      </li>
    </c:forEach>
  </ul>

  <c:if test="${configEditingEnabled}">
    <!-- Right of the tab strip. Inline SVG rather than the Font Awesome icon font header.jsp loads:
         that stylesheet comes from a CDN over a protocol-relative URL, and the control that switches
         this page between read-only and editable must not be able to render as an empty box because
         a CDN is blocked. Same reasoning as fc-dashboard.js being dependency-free. -->
    <button type="button" class="btn btn-default btn-sm fc-config-edit" id="fc-config-edit" aria-label="Edit">
      <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" focusable="false"
           style="vertical-align: -2px;">
        <path fill="currentColor" d="M12.1 1.5 14.5 3.9 5.4 13H3v-2.4zM1.5 14.5h13V16h-13z"/>
      </svg>
      Edit
    </button>
  </c:if>
</div>

<div class="tab-content">
  <c:forEach var="edge" items="${configEdges}" varStatus="status">
    <c:set var="isActive" value="${(empty activeEdgeURL and status.first) or (edge.url == activeEdgeURL)}"/>

    <div role="tabpanel" class="tab-pane ${isActive ? 'active' : ''}" id="${tabPrefix}${status.index}"
         data-edge-url="<c:out value='${edge.url}'/>" data-edge-name="<c:out value='${edge.name}'/>">
      <p/>

      <c:choose>
        <c:when test="${not edge.available}">
          <div class="alert alert-warning">
            <c:out value="${edge.url}"/> did not answer, so its <code><c:out value="${configFileName}"/></code>
            could not be read.
          </div>
        </c:when>
        <c:when test="${empty edge.content}">
          <div class="alert alert-info">
            This node has no <code>conf/<c:out value="${configFileName}"/></code>.
            <c:if test="${configEditingEnabled}">Editing and saving will create it.</c:if>
          </div>
        </c:when>
      </c:choose>

      <c:if test="${edge.available}">
        <form method="POST" action="save-config" class="fc-config-form">
          <input type="hidden" name="file" value="<c:out value='${configFileId}'/>"/>
          <input type="hidden" name="edge" value="<c:out value='${edge.url}'/>"/>
          <input type="hidden" name="scope" value="node"/>
          <input type="hidden" name="baseHash" value=""/>

          <textarea class="form-control fc-config-textarea" name="content" readonly="readonly" rows="30"
                    style="width: 100%; font-family: monospace; white-space: pre; overflow: auto;"><c:out value="${edge.content}"/></textarea>

          <c:if test="${configEditingEnabled}">
            <div class="fc-config-buttons" style="display: none; margin-top: 10px;">
              <button type="submit" class="btn btn-success btn-sm fc-config-save">
                Save to <c:out value="${edge.name}"/>
              </button>
              <button type="submit" class="btn btn-warning btn-sm fc-config-save-all">
                Save to all <c:out value="${fn:length(configEdges)}"/> nodes
              </button>
              <button type="button" class="btn btn-default btn-sm fc-config-cancel">Cancel</button>
              <span class="fc-config-hint">
                editing <code>conf/<c:out value="${configFileName}"/></code> on
                <code><c:out value="${edge.url}"/></code>
              </span>
            </div>
          </c:if>
        </form>
      </c:if>
    </div>
  </c:forEach>
</div>

<%-- No "Edges status" block here, unlike the cache screens. There it is context for a page whose
     subject is the fleet; here the edge tab strip above IS the edge list, so the block repeats it -
     and it does so BELOW a 30-row text area, where nothing that matters should live. --%>

<c:if test="${configEditingEnabled}">
  <script src="<c:url value='/static/js/fc-config-editor.js' />"></script>
  <script>
    FcConfigEditor.attach(document.getElementById("fc-config-edit"), "<c:out value='${configFileName}'/>");
  </script>
</c:if>
