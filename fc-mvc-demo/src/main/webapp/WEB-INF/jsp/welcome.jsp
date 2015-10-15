<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>


<h4>welcome page</h4>


<a href='<c:url value="/login" />'>login</a>
<a href='<c:url value="/login?logout" />'>logout</a>

<a href="<c:url value="/mvc/store/product-details-12345" />">Store MVC</a>

<a href="<c:url value="/fcmvc/store/product-details-12345" />">Store MVC + FC</a>
