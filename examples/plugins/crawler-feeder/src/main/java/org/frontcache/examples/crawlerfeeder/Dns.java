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
package org.frontcache.examples.crawlerfeeder;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/**
 * The two lookups a claimant's check needs. {@link #SYSTEM} is the JDK's resolver (the node's {@code /etc/resolv.conf}
 * and the JVM's DNS cache), which needs no module beyond {@code java.base} - the bundles' jlink'd runtime has no
 * {@code jdk.naming.dns}. The tests hand the plugin one of their own: no network in the tests.
 */
interface Dns {

	/**
	 * The name the address's PTR record gives, or null when there is none - or when the lookup failed: the JDK does
	 * not tell the two apart, and neither is a confirmation.
	 */
	String reverse(InetAddress address) throws IOException;

	/** Every address the name resolves to (A and AAAA); empty when it resolves to none. */
	List<InetAddress> forward(String host) throws IOException;

	/** The JDK's resolver. */
	Dns SYSTEM = new Dns() {
		@Override
		public String reverse(InetAddress address) {
			// a fresh instance without a host name, so the lookup is made (or answered from the JVM's cache); the JDK
			// answers the literal when there is no name - and when the name does not resolve back to the address
			String literal = address.getHostAddress();
			String name;
			try {
				name = InetAddress.getByAddress(address.getAddress()).getCanonicalHostName();
			} catch (IOException e) {
				return null;
			}
			return (null == name || name.equals(literal)) ? null : name;
		}

		@Override
		public List<InetAddress> forward(String host) throws IOException {
			return List.of(InetAddress.getAllByName(host));
		}

		@Override
		public String toString() {
			return "the JDK's resolver";
		}
	};
}
