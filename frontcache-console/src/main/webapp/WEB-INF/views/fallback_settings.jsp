<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<c:set var="activeConfig" value="fallbacks"/>
<%@ include file="/WEB-INF/views/inc/config_menu.jsp"%>

    <hr>

    <!-- one tab per edge; tab order follows the edge order from the console config -->
    <ul class="nav nav-tabs" role="tablist">
      <c:forEach var="edge" items="${fallbackConfigs}" varStatus="status">
        <li role="presentation" class="${status.first ? 'active' : ''}">
          <a href="#edge-tab-${status.index}" role="tab" data-toggle="tab">${edge.key}</a>
        </li>
      </c:forEach>
    </ul>

    <div class="tab-content">
      <!-- start loop over edges -->
      <c:forEach var="edge" items="${fallbackConfigs}" varStatus="status">

        <div role="tabpanel" class="tab-pane ${status.first ? 'active' : ''}" id="edge-tab-${status.index}">

          <table class="table table-striped">
          <thead>
            <tr>
              <th>
                <b>URL pattern</b>
              </th>
              <th>
                <b>Fallback File / Fetch URL for fallback file population</b>
              </th>
            </tr>
          </thead>
          <tbody>

          <!-- start loop over domain
                first loop over default domain
          -->
          <c:forEach var="fallbacksDomain" items="${edge.value}">

              <c:if test="${'default-domain' == fallbacksDomain.key}">
                <tr>
                  <td colspan="2" align="center"><b> Default settings  </b></td>
                </tr>

                <c:forEach var="config" items="${fallbacksDomain.value}">
                    <tr>
                      <td>${config.urlPattern}  &nbsp;</td>
                      <td>
                      ${config.fileName} <br/>
                      ${config.initUrl} <br/> &nbsp;
                      </td>
                    </tr>
                </c:forEach>
              </c:if>
          </c:forEach>

          <!--  the same loop for other domains (domain specific fallbacks)  -->
          <c:forEach var="fallbacksDomain" items="${edge.value}">
              <c:if test="${'default-domain' != fallbacksDomain.key}">
                <tr>
                  <td colspan="2" align="center"><b> ${fallbacksDomain.key}  </b></td>
                </tr>

                <c:forEach var="config" items="${fallbacksDomain.value}">
                    <tr>
                      <td>${config.urlPattern}  &nbsp;</td>
                      <td>
                      ${config.fileName} <br/>
                      ${config.initUrl} <br/> &nbsp;
                      </td>
                    </tr>
                </c:forEach>
              </c:if>
          </c:forEach>


          </tbody>
          <!-- end loop over configs -->
        </table>

        </div>
      </c:forEach>
      <!-- end loop over edges -->
    </div>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
