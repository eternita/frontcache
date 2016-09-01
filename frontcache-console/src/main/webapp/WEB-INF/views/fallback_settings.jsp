<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>


<p/> 
<p/> 

Fallback URL pattern match test tool

<form action="/fallbacks/urltest">
   <table>
    <tr>
        <td>url</td>
        <td><input type="text" name="url" value="${url}" style="width: 800px;"></td>
    </tr>
    <tr>
        <td>pattern</td>
        <td><input type="text" name="pattern" value="${pattern}" style="width: 800px;"></td>
    </tr>
    <tr>
        <td colspan="2">
            <input type="submit" value="Submit"/>
        </td>
    </tr>
    <tr>
        <td colspan="2">
        <p>&nbsp;</p><p>&nbsp;</p><p>&nbsp;</p>

         ${matchResult}
         <br/>${url}
         <br/>${pattern}
        </td>
    </tr>
   </table> 
</form>

    <hr>
    
fallback configs
    <hr>
    
    <!-- start loop over edges -->
    <c:forEach var="edge" items="${fallbackConfigs}">
      <div>Edge: <c:out value="${edge.key}"/></div>
        
	  <table>
          <tr>
            <td>
              <b>file name</b>
            </td>
            <td>
              <b>url pattern / fetch url (to load fallback data)</b>
            </td>
          </tr>
          
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
        <!-- end loop over configs -->
      </table> 
      
      <hr>
    </c:forEach>
    <!-- end loop over edges -->
    
    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>