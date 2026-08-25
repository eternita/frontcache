<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- edges status block, stacked variant (green/red dot + name, with the node's live numbers below);
     used by the realtime view where the block sits in a narrow side column. expects ${edges} in the model.

     The name and the online dot are rendered here; every NUMBER is a placeholder that fc-edges-status.js
     fills from /edges-status.json and refreshes every 10s. That is deliberate - formatting a value here
     with DecimalFormat and every later value in JavaScript would be two implementations of one format,
     and only one of them would get fixed. See docs/edges-status-realtime-proposal.md section 5.5. -->
<h2 style="font-size: 22px;">Edges status</h2>
<br/>

<div id="fc-edges-status" class="fc-edges-status">
     <table>
        <c:forEach var="edge" items="${edges}">
          <tr data-edge-url="<c:out value='${edge.url}'/>">
             <td class="fc-edge-dot">
                 <%-- the initial state is server-rendered; the poll keeps it current (data-field="dot") --%>
                 <c:choose>
                  <c:when test="${'ONLINE' == edge.onlineStatus}">
                    <img alt="status" data-field="dot" src="static/images/status/green.png" width="15px">
                  </c:when>
                  <c:otherwise>
                    <img alt="status" data-field="dot" src="static/images/status/red.png" width="15px">
                  </c:otherwise>
                 </c:choose>
             </td>
             <td class="fc-edge">
                <div class="fc-edge-name">
                    <c:out value="${edge.name}"/>
                    <span class="fc-edge-uptime" data-field="uptime"></span>
                </div>

                <%-- rendered for every edge, online or not. An offline edge shows a column of em dashes
                     and a "not reachable" note, and fills itself in on the next poll if it comes back -
                     hiding the rows instead would leave nothing for the poll to write into, so a node
                     that recovered would look dead until someone reloaded the page. --%>
                    <dl class="fc-edge-stats">
                        <dt>items cached</dt>
                        <dd data-field="cachedEntries">&mdash;</dd>

                        <dt title="the on-disk (L2) index">cache size</dt>
                        <dd data-field="cacheSize">&mdash;</dd>

                        <dt>L1 (memory)</dt>
                        <dd><span data-field="l1">&mdash;</span>
                            <span class="fc-edge-note" data-field="evictions"></span></dd>

                        <dt>L2 (Lucene)</dt>
                        <dd data-field="l2">&mdash;</dd>

                        <dt>JVM heap</dt>
                        <dd data-field="heap">&mdash;</dd>

                        <dt title="this JVM, across all cores">JVM cpu</dt>
                        <dd data-field="cpu">&mdash;</dd>
                    </dl>
                    <div class="fc-edge-offline" data-field="offline"
                         <c:if test="${'ONLINE' == edge.onlineStatus}">style="display: none"</c:if>>not reachable</div>
             </td>
         </tr>
        </c:forEach>
     </table>
</div>
