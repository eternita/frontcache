<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ taglib prefix="fc" uri="http://frontcache.org/core" %>
<fc:component maxage="0"/>

  <div class="row">
    <div class="col-sm-10" style="background-color:lavender;">header - cacheable data / TTL in cache is 30 days</div>
    
    <fc:include url="/user-profile.jsp"/>
  </div>
