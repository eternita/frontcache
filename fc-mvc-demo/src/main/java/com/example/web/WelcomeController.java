package com.example.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 *
 */
@Controller
public class WelcomeController {
    
	   @RequestMapping(value = { "/", "/welcome**" }, method = RequestMethod.GET)
	   public String index(ModelMap map) {
	       map.put("msg", "Hello Spring 4 Web MVC! from hobbyray");
	       return "welcome";
	   }
	    

}
