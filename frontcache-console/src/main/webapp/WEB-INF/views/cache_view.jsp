<%@taglib uri="http://www.springframework.org/tags/form" prefix="form"%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>


<p/> 
<p/> 

Enter cache key/url to view content


<form:form method="POST" commandName="cacheView">
   <table>
    <tr>
        <td>Edge: 
            <form:select path="edge">
               <form:options items="${edgeList}" />
            </form:select>        
        </td>
        <td>
            &nbsp;&nbsp; Key / Url&nbsp;
            <form:input path="key" style="width: 500px;" /> 
            <input type="submit" value="Submit"/>
        </td>
    </tr>
    <tr>
        <td colspan="2">
			<p/> 
			<p/>         
            Cache value: <br/>
            <form:textarea path="webResponseStr" rows="5" cols="30" style="width: 100%"/>
        </td>
    </tr>
   </table>
</form:form>

    
<p/> 
<p/> 

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>