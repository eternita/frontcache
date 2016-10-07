package org.frontcache.console.web.controllers;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.core.WebResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Controller
@RequestMapping("/cache-view")
public class CacheViewController {
	
	@Autowired
	private FrontcacheService frontcacheService;
	
	private static final DateFormat YYYY_MM_dd_HH_mm_ss_DF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");


	@RequestMapping(method = RequestMethod.GET)
	public String initForm(Model model) {
		CacheViewForm cacheViewForm = new CacheViewForm();
		model.addAttribute("cacheView", cacheViewForm);

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);
		
		return "cache_view";
	}	
    
	
	@RequestMapping(method = RequestMethod.POST)
	public String submitForm(Model model, CacheViewForm cacheViewForm) {
		model.addAttribute("cacheView", cacheViewForm);
		
		WebResponse webResponse = frontcacheService.getFromCache(cacheViewForm.getEdge(), cacheViewForm.getKey());
		if (null != webResponse)
		{
			model.addAttribute("webResponse", webResponse);
			model.addAttribute("webResponseStr", new String(webResponse.getContent()));
			Map<String, List<String>> webRespHeaders = new TreeMap<String, List<String>>();
			webRespHeaders.putAll(webResponse.getHeaders());
			model.addAttribute("webResponseHeaders", webRespHeaders);
			
			long expirationDate = webResponse.getExpireTimeMillis();
			String expirationDateStr = "UNDEFINED";
			if (-1 == expirationDate)
				expirationDateStr = "DOES NOT EXPIRE";
			else if (0 == expirationDate)
				expirationDateStr = "DYNAMIC"; // never cached (should never happened)
			else 
				expirationDateStr = YYYY_MM_dd_HH_mm_ss_DF.format(new Date(expirationDate));

			model.addAttribute("expirationDateStr", expirationDateStr);
				
		}

    	Set<String> agents = frontcacheService.getFrontCacheAgentURLs();
    	model.addAttribute("edgeList", agents);
		
		return "cache_view";
	}	
}
