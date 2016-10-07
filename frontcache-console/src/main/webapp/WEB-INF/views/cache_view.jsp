<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<p/> 
<p/> 

Enter URL (origin URL with port number) to view cached content


<form:form method="POST" commandName="cacheView">
   <table>
    <tr>
        <td>Edge: 
            <form:select path="edge">
               <form:options items="${edgeList}" />
            </form:select>        
        </td>
        <td>
            &nbsp;&nbsp; URL&nbsp;
            <form:input path="key" style="width: 500px;" /> 
            <input type="submit" value="Submit"/>
        </td>
    </tr>
    <c:choose>
	    <c:when test="${null != webResponse}">
            <tr>
                   <td> 
                        <p/><p/>         
                        <b>Expiration date:</b>
                   </td>
                   <td>
                        <p/><p/>         
                        ${expirationDateStr} 
                   </td>
            </tr>
            
		    <tr>
		        <td colspan="2">
		            <p/><p/>         
		            <b>Headers:</b>
		        </td>
		    </tr>
		    
		    <c:forEach var="map" items="${webResponseHeaders}">
		        <tr>
		           <td> 
		                ${map.key}
		           </td>
		           <td>
		            <c:forEach var="item" items="${map.value}">
		                ${item} 
		            </c:forEach>
		           </td>
		        </tr>
		    </c:forEach>

		    <tr>
		        <td colspan="2">
		            <p/><p/>         
		            <b>Cache value:</b><br/>
		            <textarea rows="20" cols="30" style="width: 100%">
		                ${webResponseStr}
		            </textarea>
		        </td>
		    </tr>
	    </c:when>
	    
	    <c:otherwise>
            <tr>
                <td colspan="2">
                    <p/><p/>         
                    <b>Nothing Found</b>
                </td>
            </tr>
	    </c:otherwise>
    </c:choose>

    
    
   </table>
</form:form>

    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>