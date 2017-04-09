<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ taglib prefix="fc" uri="http://frontcache.org/core" %>
<fc:component maxage="0"/>

<!DOCTYPE html>
<html lang="en">
<head>
  <title>Frontcache Example (using JSP and Bootstrap)</title>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css">
  <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.2.0/jquery.min.js"></script>
  <script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js"></script>
</head>
<body>

<div class="container-fluid">
  
  <fc:include url="/header.jsp"/>
  
  <div class="row">
    <div class="col-sm-12" style="background-color:lavenderblush;">
    <h1>Hello Frontcache!</h1>
    Content page - cacheable data / TTL in cache is 7 days  
    </div>
  </div>

  <fc:include url="/footer.jsp"/>
  
</div>

</body>
</html>
