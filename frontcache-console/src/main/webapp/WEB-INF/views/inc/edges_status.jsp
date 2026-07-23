<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<!-- edges status block (green/red dot + name + items cached); expects ${edges} in the model -->
<h2 style="font-size: 22px;">Edges status</h2>
<br/>

<div style="font-size: 18px;">

     <table >
        <c:forEach var="edge" items="${edges}">
          <tr>
             <td align="left" style="padding: 5px; vertical-align: middle;">
                 <c:choose>
                  <c:when test="${'ONLINE' == edge.onlineStatus}">
                    <img alt="status" src="static/images/status/green.png" width="15px">
                  </c:when>
                  <c:otherwise>
                    <img alt="status" src="static/images/status/red.png" width="15px">
                  </c:otherwise>
                 </c:choose>
             </td>
             <td align="left" style="padding: 5px;">
                <c:out value="${edge.name}"/>
             </td>
             <td align="left" style="padding-left: 20px;">
                 <c:choose>
                  <c:when test="${'ONLINE' == edge.onlineStatus}">
                    <c:out value="${edge.cachedAmountString}"/> items cached
                  </c:when>
                  <c:otherwise>
                    &nbsp;
                  </c:otherwise>
                 </c:choose>
             </td>
         </tr>
        </c:forEach>
     </table>

</div>
