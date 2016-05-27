package org.frontcache.console.web;

import java.util.Map;

import org.frontcache.console.service.FrontcacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller
public class NodesController {

	private final Logger logger = LoggerFactory.getLogger(NodesController.class);
	private final FrontcacheService frontcacheService;

	@Autowired
	public NodesController(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
	}

	@RequestMapping(value = "/", method = RequestMethod.GET)
	public String index(Map<String, Object> model) {

		logger.debug("index() is executed!");
		
		Map<String, String> cachedAmount = frontcacheService.getCachedAmount();
		model.put("cachedAmount", cachedAmount);

		model.put("hystrixMonitorURLList", frontcacheService.getHystrixMonitorURLList());
		
		return "nodes";
	}

}