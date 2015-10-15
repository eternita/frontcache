package com.example.web.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.example.domain.StoreUser;
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
	   
	   return "store/fcmvc/footer";
   }

   @RequestMapping(value = { "/fcmvc/store/header" }, method = RequestMethod.GET)
   public String header(ModelMap map) {

	   return "store/fcmvc/header";
   }

   @RequestMapping(value = { "/fcmvc/store/user-info" }, method = RequestMethod.GET)
   public String getUserProfile(ModelMap map) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();	
		
		if (null != authentication && authentication.getPrincipal() instanceof StoreUser)
		{
			StoreUser user = (StoreUser) authentication.getPrincipal();
			
			// get amount of new messages
			int newMessagesAmount = backendSrv.getNewMessagesAmount(user.getUsername());
			user.setNewMessagesAmount(newMessagesAmount);
			
			map.put("user", user);
		}
	   
       return "store/fcmvc/user_info";
   }
   
   
   @RequestMapping(value = { "/fcmvc/store/get-store-news" }, method = RequestMethod.GET)
   public String getStoreNews(ModelMap map) {

	   backendSrv.getStoreNews();
       return "store/fcmvc/news";
   }
   
}
