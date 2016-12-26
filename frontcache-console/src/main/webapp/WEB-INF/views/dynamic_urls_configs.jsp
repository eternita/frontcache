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
	  	  <caption>Edge: <c:out value="${edge.key}"/></caption>
          <tr>
            <th>
              <b>Dynamic URL pattern (mached URL goes directly to origin)</b>
            </th>
          </tr>
        </thead>
        <tbody>
        <!-- start loop over configs -->
        <c:forEach var="dynamicURL" items="${edge.value}">
          <tr>
            <td>${dynamicURL}  &nbsp;</td>
          </tr>
        </c:forEach>
        </tbody> 
        <!-- end loop over configs -->
      </table> 
      
      <hr>
    </c:forEach>
    <!-- end loop over edges -->
    
    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>