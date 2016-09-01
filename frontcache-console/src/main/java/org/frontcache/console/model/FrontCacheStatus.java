package org.frontcache.console.model;

import java.text.DecimalFormat;

public class FrontCacheStatus {

	private String name;
	private long cachedAmount;
	private boolean available;
	
	private static DecimalFormat formater = new DecimalFormat("###,###,###,###");
	
	
	public String getName() {
		return name;
	}


	public void setName(String name) {
		this.name = name;
	}


	public long getCachedAmount() {
		return cachedAmount;
	}


	public void setCachedAmount(long cachedAmount) {
		this.cachedAmount = cachedAmount;
	}


	public boolean isAvailable() {
		return available;
	}


	public void setAvailable(boolean available) {
		this.available = available;
	}
	
	public String getOnlineStatus()
	{
		return isAvailable() ? "ONLINE" : "OFFLINE";
	}

	public String getCachedAmountString() {
		return formater.format(cachedAmount);
	}

	
}
