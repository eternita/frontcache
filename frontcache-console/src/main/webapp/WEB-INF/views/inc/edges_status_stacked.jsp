<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- edges status block, stacked variant (green/red dot + name, with items cached below the name);
     used by the realtime view where the block sits in a narrow side column. expects ${edges} in the model -->
<h2 style="font-size: 22px;">Edges status</h2>
<br/>

<div style="font-size: 18px;">

     <table >
        <c:forEach var="edge" items="${edges}">
          <tr>
             <td align="left" style="padding: 5px 5px 15px 5px; vertical-align: top;">
                 <c:choose>
                  <c:when test="${'ONLINE' == edge.onlineStatus}">
                    <img alt="status" src="static/images/status/green.png" width="15px">
                  </c:when>
                  <c:otherwise>
                    <img alt="status" src="static/images/status/red.png" width="15px">
                  </c:otherwise>
                 </c:choose>
             </td>
             <td align="left" style="padding: 5px 5px 15px 5px; vertical-align: top;">
                <div><c:out value="${edge.name}"/></div>
                <c:if test="${'ONLINE' == edge.onlineStatus}">
                    <div style="font-size: 15px; color: #777;"><c:out value="${edge.cachedAmountString}"/> items cached</div>
                </c:if>
             </td>
         </tr>
        </c:forEach>
     </table>

</div>
