<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ taglib prefix="fc" uri="http://frontcache.org/core" %>
<fc:component maxage="0"/>

<!DOCTYPE html>
<html lang="en">
<head>
  <title>Frontcache Example (with JSP and Bootstrap)</title>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="shortcut icon" href="/favicon.ico" type="image/x-icon"/> 
  <link rel="stylesheet" href="/bootstrap/css/bootstrap.min.css">
  <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.2.0/jquery.min.js"></script>
  <script src="/bootstrap/js/bootstrap.min.js"></script>
  <style type="text/css">
	       .demo-header {height: 120px;}
	       .demo-content {height: 250px; background-color:lavenderblush; padding-top: 100px; padding-left: 200px; }
	       .demo-footer {height: 100px; background-color:lavender; padding-top: 30px; padding-left: 200px; }
	       .demo-legend {padding-top: 10px; padding-left: 10px; }
  </style>  
</head>

<body>

<div class="container">
  
  <fc:include url="/header.jsp"/>
  
  <div class="row">
    <div class="col-sm-12 demo-content">
        <b>Page Content</b> 
        <br/>User/Bot: TTL = 3h (from cache)
    </div>
  </div>

  <fc:include url="/footer.jsp"/>
  
</div>

</body>
</html>
