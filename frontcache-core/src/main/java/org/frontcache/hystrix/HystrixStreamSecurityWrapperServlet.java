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
