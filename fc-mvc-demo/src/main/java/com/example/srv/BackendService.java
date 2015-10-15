package com.example.srv;

import java.util.logging.Logger;

import org.springframework.stereotype.Service;

@Service
public class BackendService {

	private Logger logger = Logger.getLogger(getClass().getName());

	
	public int getNewMessagesAmount(String username) {
		delay(100);
		int newMessagesAmount = 3;
		return newMessagesAmount;
	}
	
	public String getProduct(String productId) {
		delay(200);
		logger.info("get product" + productId);
		return "product";
	}

	public String getProductRecommendations(String productId) {
		delay(100);
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
