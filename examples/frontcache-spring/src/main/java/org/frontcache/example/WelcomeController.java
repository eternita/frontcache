package org.frontcache.example;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class WelcomeController {

  private static final Logger logger = LoggerFactory.getLogger(WelcomeController.class);
  
  @RequestMapping("/example")
  public String download(Map<String, Object> model) {
    logger.info("Processing /example request");
    return "index";
  }

  @RequestMapping("/")
  public String home() {
    return "redirect:/example";
  }

  @RequestMapping("/example/header")
  public String header() {
    logger.info("Processing /example/header request");
    return "header";
  }

  @RequestMapping("/example/footer")
  public String footer() {
    logger.info("Processing /example/footer request");
    return "footer";
  }

  @RequestMapping("/example/user-profile")
  public String userProfile() {
    logger.info("Processing /example/user-profile request");
    return "user-profile";
  }
}
