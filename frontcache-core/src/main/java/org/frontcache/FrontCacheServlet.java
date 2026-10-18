/*
 * Copyright 2015 Eternita
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
package org.frontcache;

import java.io.IOException;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.module.FrontcacheModules;
import org.frontcache.module.NodeMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	protected Logger logger = LoggerFactory.getLogger(getClass());

	FrontCacheEngine fcEngine = null;

	public FrontCacheServlet() {
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		// before the engine exists, so a module learns how the node is deployed before it starts (the
		// Pro module's first usage record then says so - a node in filter mode is one application replica)
		FrontcacheModules.modeKnown(NodeMode.STANDALONE);

		fcEngine = FrontCacheEngine.getFrontCache();

		return;
	}

	@Override
	public void destroy() {
		super.destroy();
		FrontCacheEngine.destroy();
		fcEngine = null;
	}


	@Override
	public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
        try {
            fcEngine.processRequest((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse, null);
        } catch (Throwable e) {
        	e.printStackTrace();
        	// TODO: handle error
        }
	}


}
