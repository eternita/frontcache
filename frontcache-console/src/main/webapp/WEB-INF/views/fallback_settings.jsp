<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<p/> 
<p/> 

<h3 align="center">Fallback URL pattern match test tool</h3>
<form action="fallbacks-urltest">
  <div class="form-group">
    <label for="url">Url:</label>
    <input type="text" name="url" class="form-control" value="${url}" id="url" placeholder="Url" style="width: 100%">
  </div>
  <div class="form-group">
    <label for="pattern">Pattern:</label>
    <input type="text" name="pattern" class="form-control" id="pattern" placeholder="Pattern" value="${pattern}" style="width: 100%">
  </div>
  <input type="submit" value="Submit" class="btn btn-success"/>
</form>

   <table class="table table-striped" style="width: 100%">
    <tbody>
    <tr>
        <td colspan="2">
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
          <tr>
            <th>
              <b>Edge: ${edge.key} - URL pattern</b>
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
      
      <hr>
    </c:forEach>
    <!-- end loop over edges -->
    
    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>