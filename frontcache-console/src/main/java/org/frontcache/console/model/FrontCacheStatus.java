/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.model;

import java.text.DecimalFormat;

public class FrontCacheStatus {

	private String name;
	private String url;
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


	public String getUrl() {
		return url;
	}


	public void setUrl(String url) {
		this.url = url;
	}
	
}
