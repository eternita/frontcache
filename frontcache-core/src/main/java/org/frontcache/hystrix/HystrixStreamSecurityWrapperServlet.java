package org.frontcache.hystrix;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.contrib.metrics.eventstream.HystrixMetricsStreamServlet;

@SuppressWarnings("serial")
public class HystrixStreamSecurityWrapperServlet extends HystrixMetricsStreamServlet {

	private String managementScheme = null; // management scheme for security

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public HystrixStreamSecurityWrapperServlet() {
		super();
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		managementScheme = FCConfig.getProperty("front-cache.management-scheme");
		if (null == managementScheme)
			logger.warn("Connector Sheme is not configured for Hystrix Stream. Hystrix Stream is accessible for all connectors");
		
		return;
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (null != managementScheme && !request.getScheme().equals(managementScheme))
		{
			String msg = "Accessing Management URL with wrong scheme" + request.getScheme();
			logger.info(msg);
			response.getOutputStream().write(msg.getBytes());
			return;
		}

		super.doGet(request, response);
	}
	
}
