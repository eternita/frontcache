<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/>
<p/>


    <hr>
    <h2 align="center">Bot Configs</h2>
    <hr>

    <!-- one tab per edge; tab order follows the edge order from the console config -->
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${botConfigs}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab">${edge.key}</a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- start loop over edges - Map <clusterNode, Map <domain, Set <BotConfgi>>> -->
      <c:forEach var="edge" items="${botConfigs}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">

          <table class="table table-striped">
            <thead>
            <tr>
              <th>
                <b>Bot pattern (for User-Agent header)</b>
              </th>
            </tr>
          </thead>
          <tbody>
          <!-- start loop over domain
                first loop over default domain
          -->
          <c:forEach var="botsDomain" items="${edge.value}">

            <c:if test="${'default-domain' == botsDomain.key}">
              <tr>
                <td align="center"><b> Default settings  </b></td>
              </tr>

              <c:forEach var="bot" items="${botsDomain.value}">
                  <tr>
                    <td>${bot}  &nbsp;</td>
                  </tr>
              </c:forEach>
            </c:if>
          </c:forEach>

          <!--  the same loop for other domains (domain specific bots)  -->
          <c:forEach var="botsDomain" items="${edge.value}">

              <c:if test="${'default-domain' != botsDomain.key}">
                <tr>
                  <td align="center"><b> ${botsDomain.key}  </b></td>
                </tr>

                <c:forEach var="bot" items="${botsDomain.value}">
                    <tr>
                      <td>${bot}  &nbsp;</td>
                    </tr>
                </c:forEach>
              </c:if>
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
