<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport" content="width=device-width, initial-scale=1">

<title>MVC demo</title>

<!-- Bootstrap -->
<link href="/fc-mvc-demo/resources/css/bootstrap.min.css" rel="stylesheet">
<link href="/fc-mvc-demo/resources/css/site.css" rel="stylesheet">

</head>
<body style="position:relative;width:960px;margin-left:auto;margin-right:auto">

	<div class="container-fluid col-lg-12 col-md-12 col-xs-12" 
		align="center" >

		<!-- start header -->
		<div align="center" class="row l r t b" >
			<%@ include file="/WEB-INF/jsp/store/mvc/header.jsp" %>			
		</div>
		<!-- end header -->


		<!-- start main area -->
		<div align="center" class="row l r " >

			<div class="col-lg-8 r b cacheable" style="height: 400px;">
			Product details
			</div>

			<div class="col-lg-4 not-cacheable" style="height: 400px;">
			Recommended products
			</div>		
		</div>
		
		<!-- start news -->
		<div align="center" class="row l r t" >
			<div class="col-lg-4 cacheable" style="height: 200px;">
			new a
			</div>		
			<div class="col-lg-4 l cacheable" style="height: 200px;">
			new b
			</div>		
			<div class="col-lg-4 l cacheable" style="height: 200px;">
			new c
			</div>		
		</div>
		<!-- end news -->
		<!-- end main area -->


		<!-- start footer -->
		<div align="center" class="row l r t b" >

			<div class="col-lg-12 cacheable" style="height: 100px;">
			Footer
			</div>
		</div>
		<!-- end footer -->



	</div>






</body>
</html>