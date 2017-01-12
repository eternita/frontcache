<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/> 
<p/> 

   
    <hr>
    <h2 align="center">Dynamic URLs Configs</h2>
    <hr>
    
    <!-- start loop over edges -->
    <c:forEach var="edge" items="${dynamicURLsConfigs}">
        
	  <table class="table table-striped">
	  <thead>
          <tr>
            <th>
              <b>Edge: ${edge.key} - Dynamic URL pattern (mached URL goes directly to origin)</b>
            </th>
          </tr>
        </thead>
        <tbody>
        
        <!-- start loop over domain 
              first loop over default domain
        -->
        <c:forEach var="dynamicURLDomain" items="${edge.value}">
        
            <c:if test="${'default-domain' == dynamicURLDomain.key}">
              <tr>
                <td align="center"><b> Default settings  </b></td>
              </tr>
              
              <c:forEach var="dynamicURL" items="${dynamicURLDomain.value}">
                  <tr>
                    <td>${dynamicURL}  &nbsp;</td>
                  </tr>
              </c:forEach>
            </c:if>
        </c:forEach>
        
        <!--  the same loop for other domains (domain specific dynamic URLs)  -->        
        <c:forEach var="dynamicURLDomain" items="${edge.value}">
        
            <c:if test="${'default-domain' != dynamicURLDomain.key}">
              <tr>
                <td align="center"><b> ${dynamicURLDomain.key}  </b></td>
              </tr>
              
              <c:forEach var="dynamicURL" items="${dynamicURLDomain.value}">
                  <tr>
                    <td>${dynamicURL}  &nbsp;</td>
                  </tr>
              </c:forEach>
            </c:if>
        </c:forEach>
        


        </tbody> 
        <!-- end loop over domains -->
      </table> 
      
      
      
      <hr>
    </c:forEach>
    <!-- end loop over edges -->
    
    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>