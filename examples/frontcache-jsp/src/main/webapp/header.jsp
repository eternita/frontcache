<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ taglib prefix="fc" uri="http://frontcache.org/core" %>
<fc:component maxage="30d"/>

  <div class="row">
    
    <div class="col-sm-9 demo-header" style="background-color:lavender; padding-top: 30px; padding-left: 200px; ">
    
        <b>Page Header</b> 
        <br/>User/Bot: TTL = 30d (from cache)
    
    </div>
    
    <fc:include url="/user-profile.jsp"/>
        
  </div>
