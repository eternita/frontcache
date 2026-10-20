<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%
try {
	Thread.sleep(15000);
} catch (InterruptedException e) {
	// TODO Auto-generated catch block
	e.printStackTrace();
}
%>
<c:set var="greetings" value="Hi"></c:set>
<c:out value="${greetings}"></c:out> from Resilience
