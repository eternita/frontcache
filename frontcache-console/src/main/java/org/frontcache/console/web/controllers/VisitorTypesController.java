/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.console.web.controllers;

import java.util.Map;

import org.frontcache.console.model.VisitorTypesConfig;
import org.frontcache.console.service.FrontcacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


/**
 * The Visitor Types screen: how each edge classifies a request as {@code bot} or {@code guest}, in
 * evaluation order, with hit counts. The raw conf/bots.conf behind it is a separate screen, served
 * by {@link BotConfigController} under Configs - the same split guard has between /guard-rules and
 * /guard-configs.
 *
 * This lived at /bot-configs before 2.9.0, which is a URL somebody may have bookmarked, so that path
 * is NOT silently repurposed: it now serves the raw file, and this screen answers under its own name.
 */
@Controller
public class VisitorTypesController {

	@Autowired
	private FrontcacheService frontcacheService;

    @RequestMapping(value = "/visitor-types", method = RequestMethod.GET)
    public String getVisitorTypes(ModelMap model) {

    	Map<String, VisitorTypesConfig> visitorTypes = frontcacheService.getVisitorTypes();
		model.put("visitorTypes", visitorTypes);

        return "visitor_types";
    }


}
