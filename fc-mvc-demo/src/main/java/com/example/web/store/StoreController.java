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
public class StoreController {

	@Autowired
	BackendService backendSrv;

	@RequestMapping(value = { "/mvc/store" }, method = RequestMethod.GET)
	public String store(@PathVariable String productId, ModelMap map) {
		return "mvc/welcome";
	}

	@RequestMapping(value = { "/mvc/store/products" }, method = RequestMethod.GET)
	public String productList(ModelMap map) {
		return "mvc/products";
	}
	
	@RequestMapping(value = { "/mvc/store/product-details-{productId}" }, method = RequestMethod.GET)
	public String productDetails(@PathVariable String productId, ModelMap map) {

//		backendSrv.getMainMenu();

		backendSrv.getUserdata();

		backendSrv.getProduct(productId);

		backendSrv.getProductRecommendations(productId);

//		backendSrv.getFooterData();

		backendSrv.getStoreNews();

		map.put("msg", "Hello Spring 4 Web MVC! from hobbyray");
		return "store/mvc/_product_details_page";
	}

}
