package com.example.srv;

import java.util.logging.Logger;

import org.springframework.stereotype.Service;

@Service
public class BackendService {

	private Logger logger = Logger.getLogger(getClass().getName());
	
//	public String getMainMenu() {
//		delay(100);
//		return "main menu : catalog1 | catalog2 | catalog3 | deals | delivery | about us";
//	}
//	
//	
//	public String getFooterData() {
//		delay(100);
//		return "footer: find location | return policy | about us | ...";
//	}

	public String getUserdata() {
		delay(100);
		return "userData";
	}
	
	public String getProduct(String productId) {
		delay(200);
		logger.info("get product" + productId);
		return "product";
	}

	public String getProductRecommendations(String productId) {
		delay(300);
		return "product recommendations";
	}

	public String getProductReviews() {
		delay(100);
		return "product revews";
	}

	public String getSponsoredProducts(String productId) {
		delay(300);
		return "sponsored products";
	}
	
	public String getFrequentlyBoughtTogeather(String productId) {
		delay(300);
		return "sponsored products";
	}
	
	public String getStoreNews() {
		delay(300);
		return "store news";
	}

	
	/**
	 * delay in milliseconds
	 * 
	 * @param ms
	 */
	private void delay(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

}
