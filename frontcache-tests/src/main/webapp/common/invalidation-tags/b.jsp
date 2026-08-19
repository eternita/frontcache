<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="http://frontcache.org/core" prefix="fc" %>
<c:set var="fruit" value="banana"></c:set><fc:component maxage="-1" tags="apple|${fruit}|orange" />a