<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/>
<p/>


    <hr>
    <h2 align="center">Dynamic URLs Configs</h2>
    <hr>

    <!-- one tab per edge; tab order follows the edge order from the console config -->
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${dynamicURLsConfigs}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab">${edge.key}</a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- start loop over edges -->
      <c:forEach var="edge" items="${dynamicURLsConfigs}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">

          <table class="table table-striped">
          <tbody>

          <!-- start loop over domain
                first loop over default domain
          -->

          <!--  the same loop for other domains (domain specific dynamic URLs)  -->
          <c:forEach var="dynamicURLDomain" items="${edge.value}">
            <c:if test="${'default-domain' != dynamicURLDomain.key}">
                <tr>
                  <td align="center"><b>
                      ${dynamicURLDomain.key}
                  </b></td>
                </tr>
            </c:if>

            <c:forEach var="dynamicURL" items="${dynamicURLDomain.value}">
                <tr>
                  <td>${dynamicURL}  &nbsp;</td>
                </tr>
            </c:forEach>
          </c:forEach>

          </tbody>
          <!-- end loop over domains -->
        </table>

        </div>
      </c:forEach>
      <!-- end loop over edges -->
    </div>


<p/>
<p/>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>