<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- edges status block, wide variant (dot + name + items cached on one row); used by the cache pages,
     where the block is context rather than the subject of the page. expects ${edges} in the model -
     names and URLs only, from FrontcacheService.getEdgeList(), which makes no network calls.

     Same data-field markup and same poller as the stacked variant on the realtime page: the dot and the
     count are placeholders that fc-edges-status.js fills from /edges-status.json every 10s. Deliberately
     NOT the stacked variant's six rows - on a cache or config page this block is a sidebar, and six rows
     per edge would push the page's actual content down. One renderer, two amounts of detail: the JS
     writes only the fields a page asks for. -->
<h2 style="font-size: 22px;">Edges status</h2>
<br/>

<div id="fc-edges-status" class="fc-edges-status fc-edges-status-wide">
     <table>
        <c:forEach var="edge" items="${edges}">
          <tr data-edge-url="<c:out value='${edge.url}'/>">
             <td style="padding: 5px; vertical-align: middle;">
                 <span class="fc-dot fc-dot-pending" data-field="dot" title="checking ..."></span>
             </td>
             <td style="padding: 5px;">
                <c:out value="${edge.name}"/>
             </td>
             <td style="padding-left: 20px;">
                <span data-field="cachedEntries">&mdash;</span> items cached
                <span class="fc-edge-offline" data-field="offline" style="display: none">not reachable</span>
             </td>
         </tr>
        </c:forEach>
     </table>
</div>

<script src="<c:url value='/static/js/fc-edges-status.js' />"></script>
<script>
	FcEdgesStatus.attach(document.getElementById("fc-edges-status"), "edges-status.json");
</script>
