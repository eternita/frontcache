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
package org.frontcache;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes Frontcache configuration at webapp startup, before any servlet handles a request.
 *
 * FCConfig's static initializer installs hystrix.properties into Archaius, but Archaius accepts an
 * install only once. The Hystrix metrics-stream servlet (FrontcacheHystrixMetricsStreamServlet) has
 * a static field that touches Archaius (DynamicPropertyFactory.getInstance()) during class init, so
 * if /hystrix.stream is hit before any page request (the console polls it continuously), Archaius
 * gets default config first and FCConfig's per-command overrides (timeouts, thresholds) are dropped.
 *
 * contextInitialized() fires before all servlets are initialized, regardless of load-on-startup, so
 * installing the config here guarantees the overrides win that race.
 */
public class FrontcacheConfigBootstrapListener implements ServletContextListener {

	private Logger logger = LoggerFactory.getLogger(FrontcacheConfigBootstrapListener.class);

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		FCConfig.ensureInitialized();
		logger.info("Frontcache configuration initialized at startup (hystrix.properties installed before servlets load)");
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
	}

}
