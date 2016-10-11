<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%
    response.setCharacterEncoding("UTF-8");
%>

<p/> 
<p/> 

<form:form method="POST" class="form-inline" commandName="cacheView">
  <div class="form-group">
    <label for="URL">URL</label>
    <form:input class="form-control" placeholder="http://your.site.com" path="key" style="width: 500px;" />
  </div>   
  <div class="form-group">
    <label for="edge">Edge: </label>
            <form:select class="form-control" path="edge">
               <form:options items="${edgeList}" />
            </form:select>      
  </div>
  <button type="submit" class="btn btn-success">View cache</button>
  </form:form>
  
  <table style="width: 100%;">
  <tbody>
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
  </tbody>  
   </table>

    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>