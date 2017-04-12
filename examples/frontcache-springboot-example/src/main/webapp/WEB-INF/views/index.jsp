<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ taglib prefix="fc" uri="http://frontcache.org/core" %>
<fc:component maxage="1m"/>

<!DOCTYPE html>
<html lang="en">
<head>
  <title>Frontcache + Spring Boot Demo</title>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="shortcut icon" href="/favicon.ico" type="image/x-icon"/> 
  <link rel="stylesheet" type="text/css" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css" />
  <style type="text/css">
	       .demo-header {height: 120px; background-color:lavender;}
	       .demo-content {height: 250px; background-color:lavenderblush; padding-top: 100px; padding-left: 200px; }
	       .demo-footer {height: 100px; background-color:lavender; padding-top: 30px; padding-left: 200px; }
  </style>  
</head>

<body>

<div class="container">
  
  <fc:include url="/example/header"/>
  
  <div class="row">
    <div class="col-sm-12 demo-content">
        <b>Page Content</b> 
        <br/>User/Bot: TTL = 1 min (from cache)
    </div>
  </div>

  <fc:include url="/example/footer"/>
  
</div>

</body>
</html>
