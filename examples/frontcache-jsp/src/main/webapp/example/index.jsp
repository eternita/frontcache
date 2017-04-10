<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ taglib prefix="fc" uri="http://frontcache.org/core" %>
<fc:component maxage="1m"/>

<!DOCTYPE html>
<html lang="en">
<head>
  <title>Frontcache demo [JSP]</title>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="shortcut icon" href="/favicon.ico" type="image/x-icon"/> 
  <link rel="stylesheet" href="/bootstrap/css/bootstrap.min.css">
  <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.2.0/jquery.min.js"></script>
  <script src="/bootstrap/js/bootstrap.min.js"></script>
  <style type="text/css">
	       .demo-header {height: 120px; background-color:lavender;}
	       .demo-content {height: 250px; background-color:lavenderblush; padding-top: 100px; padding-left: 200px; }
	       .demo-footer {height: 100px; background-color:lavender; padding-top: 30px; padding-left: 200px; }
  </style>  
</head>

<body>

<div class="container">
  
  <fc:include url="/example/header.jsp"/>
  
  <div class="row">
    <div class="col-sm-12 demo-content">
        <b>Page Content</b> 
        <br/>User/Bot: TTL = 1 min (from cache)
    </div>
  </div>

  <fc:include url="/example/footer.jsp"/>
  
</div>

</body>
</html>
