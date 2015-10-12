<%@ taglib uri="http://frontcache.org/core" prefix="fc" %>

<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport" content="width=device-width, initial-scale=1">

<title>MVC + FC demo</title>

<!-- Bootstrap -->
<link href="/fc-mvc-demo/resources/css/bootstrap.min.css" rel="stylesheet">
<link href="/fc-mvc-demo/resources/css/site.css" rel="stylesheet">

</head>
<body style="position:relative;width:960px;margin-left:auto;margin-right:auto">

	<div class="container-fluid col-lg-12 col-md-12 col-xs-12" 
		align="center" >


		<!-- start header -->
		<div align="center" class="row l r t b" >
		
			<fc:include url="/fcmvc/store/header" />
			
		</div>
		<!-- end header -->



		<!-- start main area -->
		<div align="center" class="row l r " >

			<div class="col-lg-8 r b cacheable" style="height: 400px;">
				
				<fc:include url="/fcmvc/store/include-product-details-${productId}" />
				
			</div>

			<div class="col-lg-4 not-cacheable" style="height: 400px;">
				
				<fc:include url="/fcmvc/store/include-product-recommendations-${productId}" />
				
			</div>		

		</div>
		
		<!-- start news -->
		<div align="center" class="row l r t" >
				
				<fc:include url="/fcmvc/store/get-store-news" />
				
		</div>
		<!-- end news -->
		
		<!-- end main area -->



		<!-- start footer -->
		<div align="center" class="row l r t b" >
			
			<fc:include url="/fcmvc/store/footer" />
			
		</div>
		<!-- end footer -->




	</div>






</body>
</html>