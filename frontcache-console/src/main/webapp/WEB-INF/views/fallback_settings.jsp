<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/> 
<p/> 

<h3 align="center">Fallback URL pattern match test tool</h3>
<form action="/fallbacks/urltest">
  <div class="form-group">
    <label for="url">Url:</label>
    <input type="text" class="form-control" value="${url}" id="url" placeholder="Url" style="width: 800px;">
  </div>
  <div class="form-group">
    <label for="pattern">Pattern:</label>
    <input type="text" class="form-control" id="pattern" placeholder="Pattern" value="${pattern}" style="width: 800px;">
  </div>
  <input type="submit" value="Submit" class="btn btn-success"/>
</form>

   <table class="table table-striped">
    <tbody>
    <tr>
        <td colspan="2">
        <p>&nbsp;</p><p>&nbsp;</p><p>&nbsp;</p>

         ${matchResult}
         <br/>${url}
         <br/>${pattern}
        </td>
    </tr>
    </tbody>
   </table>
   
    <hr>
    <h2 align="center">Fallback Configs</h2>
    <hr>
    
    <!-- start loop over edges -->
    <c:forEach var="edge" items="${fallbackConfigs}">
        
	  <table class="table table-striped">
	  <thead>
	  	  <caption>Edge: <c:out value="${edge.key}"/></caption>
          <tr>
            <th>
              <b>file name</b>
            </th>
            <th>
              <b>url pattern / fetch url (to load fallback data)</b>
            </th>
          </tr>
        </thead>
        <tbody>
        <!-- start loop over configs -->
        <c:forEach var="config" items="${edge.value}">
          <tr>
            <td>${config.fileName}  &nbsp;</td>
            <td>
            ${config.urlPattern} <br/>
            ${config.initUrl} <br/> &nbsp;
            </td>
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