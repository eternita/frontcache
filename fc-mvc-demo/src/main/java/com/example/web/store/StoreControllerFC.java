package com.example.web.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.example.srv.BackendService;


/**
 *
 */
@Controller
public class StoreControllerFC {
    
	
	@Autowired
	BackendService backendSrv;
	
   @RequestMapping(value = { "/fcmvc/store/product-details-{productId}" }, method = RequestMethod.GET)
   public String productDetails(@PathVariable String productId, ModelMap map) {

	   map.put("productId", productId);

	   return "store/fcmvc/_product_details_page";
   }
    

   @RequestMapping(value = { "/fcmvc/store/include-product-details-{productId}" }, method = RequestMethod.GET)
   public String includeProductDetails(@PathVariable String productId, ModelMap map) {
	   
	   
	   backendSrv.getProduct(productId);
	   map.put("productId", productId);
	   	   
	   return "store/fcmvc/product_details";
   }
   
   @RequestMapping(value = { "/fcmvc/store/include-product-recommendations-{productId}" }, method = RequestMethod.GET)
   public String includeProductRecommendations(@PathVariable String productId, ModelMap map) {
	   
	   backendSrv.getProductRecommendations(productId);
	   map.put("productId", productId);
	   	   
	   return "store/fcmvc/product_recommendations";
   }
   
   @RequestMapping(value = { "/fcmvc/store/footer" }, method = RequestMethod.GET)
   public String footer(ModelMap map) {
	   
//	   backendSrv.getFooterData();
	   
	   return "store/fcmvc/footer";
   }

   @RequestMapping(value = { "/fcmvc/store/header" }, method = RequestMethod.GET)
   public String header(ModelMap map) {

	   return "store/fcmvc/header";
   }


   
   @RequestMapping(value = { "/fcmvc/store/user-info" }, method = RequestMethod.GET)
   public String headerLogo(ModelMap map) {

	   backendSrv.getUserdata();
       return "store/fcmvc/user_info";
   }
   
   
   @RequestMapping(value = { "/fcmvc/store/get-store-news" }, method = RequestMethod.GET)
   public String getStoreNews(ModelMap map) {

	   backendSrv.getStoreNews();
       return "store/fcmvc/news";
   }
   
}
