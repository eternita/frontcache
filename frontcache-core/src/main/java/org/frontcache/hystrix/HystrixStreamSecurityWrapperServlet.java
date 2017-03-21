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
package org.frontcache.hystrix;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.FCConfig;
import org.frontcache.hystrix.stream.FrontcacheHystrixMetricsStreamServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
public class HystrixStreamSecurityWrapperServlet extends FrontcacheHystrixMetricsStreamServlet {

	private int managementPort = -1; // management port for security

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public HystrixStreamSecurityWrapperServlet() {
		super();
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		String managementPortStr = FCConfig.getProperty("front-cache.management.port");
		if (null == managementPortStr || managementPortStr.trim().length() == 0)
			logger.warn("Frontcache Hystrix Stream is not restricted to specific port. Hystrix Stream is accessible for all connectors");
		else {
			try
			{
				managementPort = Integer.parseInt(managementPortStr);
			} catch (Exception ex) {
				ex.printStackTrace();
				logger.error("Can't read managementPort=" + managementPortStr + ". Frontcache Hystrix Stream is not restricted to specific port. Hystrix Stream is accessible for all connectors");
			}
		}
		
		return;
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (-1 < managementPort && managementPort != request.getServerPort())
		{
			String msg = "Accessing Management URL with wrong port " + request.getServerPort();
			logger.info(msg);
			response.getOutputStream().write(msg.getBytes());
			return;
		}

		super.doGet(request, response);
	}
	
}
