<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/> 
<p/> 

   
    <hr>
    <h2 align="center">Bot Configs</h2>
    <hr>
    
    <!-- start loop over edges -->
    <c:forEach var="edge" items="${botConfigs}">
        
	  <table class="table table-striped">
	  <thead>
	  	  <caption>Edge: <c:out value="${edge.key}"/></caption>
          <tr>
            <th>
              <b>Bot pattern (for User-Agent header)</b>
            </th>
          </tr>
        </thead>
        <tbody>
        <!-- start loop over configs -->
        <c:forEach var="bot" items="${edge.value}">
          <tr>
            <td>${bot}  &nbsp;</td>
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