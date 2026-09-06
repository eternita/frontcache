<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- edges status block, stacked variant (dot + name, with the node's live numbers below);
     used by the realtime view where the block sits in a narrow side column. expects ${edges} in the
     model - names and URLs only, from FrontcacheService.getEdgeList(), which makes no network calls.

     Nothing rendered here is live data. Every value is a placeholder that fc-edges-status.js fills from
     /edges-status.json and refreshes every 10s, INCLUDING the status dot. Rendering the numbers
     server-side meant a fan-out over the whole fleet before the page could paint, so one unreachable
     edge held up a monitoring dashboard; and it meant two implementations of every format, one in
     DecimalFormat for the first paint and one in JavaScript for every paint after it.
     See docs/archive/edges-status-realtime-proposal.md sections 5.5 and 8. -->
<h2 style="font-size: 22px;">Edges status</h2>
<br/>

<div id="fc-edges-status" class="fc-edges-status">
     <table>
        <c:forEach var="edge" items="${edges}">
          <tr data-edge-url="<c:out value='${edge.url}'/>">
             <td class="fc-edge-dot">
                 <%-- pending until the first poll answers: this console has not asked yet, and a red
                      dot on a healthy node is a worse answer than an honest "checking" --%>
                 <span class="fc-dot fc-dot-pending" data-field="dot" title="checking ..."></span>
             </td>
             <td class="fc-edge">
                <div class="fc-edge-name">
                    <span class="fc-edge-host" title="<c:out value='${edge.name}'/>"><c:out value="${edge.name}"/></span>
                    <span class="fc-edge-uptime" data-field="uptime"></span>
                </div>

                <%-- rendered for every edge, online or not. An offline edge shows a column of em dashes
                     and a "not reachable" note, and fills itself in on the next poll if it comes back -
                     hiding the rows instead would leave nothing for the poll to write into, so a node
                     that recovered would look dead until someone reloaded the page. --%>
                <dl class="fc-edge-stats">
                    <dt>Items cached</dt>
                    <dd data-field="cachedEntries">&mdash;</dd>

                    <dt title="the on-disk (L2) index">Cache size</dt>
                    <dd data-field="cacheSize">&mdash;</dd>

                    <dt>L1 (memory)</dt>
                    <dd data-field="l1">&mdash;</dd>

                    <dt>L2 (Lucene)</dt>
                    <dd data-field="l2">&mdash;</dd>

                    <dt>JVM heap</dt>
                    <dd data-field="heap">&mdash;</dd>

                    <dt title="this JVM, across all cores">JVM CPU</dt>
                    <dd data-field="cpu">&mdash;</dd>
                </dl>
                <div class="fc-edge-offline" data-field="offline" style="display: none">not reachable</div>
             </td>
         </tr>
        </c:forEach>
     </table>
</div>

<%-- The block carries its own poller, so any page that includes it gets live numbers without having to
     remember to wire one up. --%>
<script src="<c:url value='/static/js/fc-edges-status.js' />"></script>
<script>
	FcEdgesStatus.attach(document.getElementById("fc-edges-status"), "edges-status.json");
</script>
