<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
try {
	Thread.sleep(200);
} catch (InterruptedException e) {
	// TODO Auto-generated catch block
	e.printStackTrace();
}
%>
<c:set var="greetings" value="Hi"></c:set>
<c:out value="${greetings}"></c:out> from Hystrix
