<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/> 
<p/> 

   
    <hr>
    <h2 align="center">Bot Configs</h2>
    <hr>
    
    <!-- start loop over edges - Map <clusterNode, Map <domain, Set <BotConfgi>>> -->
    <c:forEach var="edge" items="${botConfigs}">
        
	  <table class="table table-striped">
	    <thead>
          <tr>
            <th>
              <b>Edge: ${edge.key} - Bot pattern (for User-Agent header)</b>
            </th>
          </tr>
        </thead>
        <tbody>
        <!-- start loop over domain 
              first loop over default domain
        -->
        <c:forEach var="botsDomain" items="${edge.value}">
        
	        <c:if test="${'DEFAULT_DOMAIN' == botsDomain.key}">
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
        
        <!--  the same loop for other domains (domain specific bots)  -->        
        <c:forEach var="botsDomain" items="${edge.value}">
        
            <c:if test="${'DEFAULT_DOMAIN' != botsDomain.key}">
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
      
      <hr>
    </c:forEach>
    <!-- end loop over edges -->
    
    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>