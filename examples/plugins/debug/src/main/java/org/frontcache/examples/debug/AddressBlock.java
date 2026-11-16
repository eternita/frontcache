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
package org.frontcache.examples.debug;

import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * An address block - {@code 127.0.0.1/32}, {@code ::1/128}, or a bare address - compared on raw bytes, so an IPv4
 * block never covers an IPv6 address. Parsed as a literal only: never a host name, so never a DNS lookup.
 */
record AddressBlock(byte[] network, int bits, String text) {

	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
	private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

	/** @return the block, or null when the text is not one */
	static AddressBlock parse(String text) {
		if (null == text)
			return null;
		String value = text.trim();
		int bits = -1;
		int slash = value.indexOf('/');
		if (slash >= 0)
		{
			try {
				bits = Integer.parseInt(value.substring(slash + 1));
			} catch (NumberFormatException e) {
				return null;
			}
			value = value.substring(0, slash);
		}
		InetAddress address = literal(value);
		if (null == address)
			return null;
		byte[] bytes = address.getAddress();
		int max = bytes.length * 8;
		if (bits < 0)
			bits = max;
		if (bits > max)
			return null;
		return new AddressBlock(bytes, bits, text.trim());
	}

	/** An address literal, or null - never a host name. */
	static InetAddress literal(String text) {
		if (null == text || text.isEmpty())
			return null;
		boolean v6 = text.indexOf(':') >= 0;
		if (v6 ? !IPV6.matcher(text).matches() : !IPV4.matcher(text).matches())
			return null;
		try {
			return InetAddress.ofLiteral(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	boolean contains(String addressLiteral) {
		InetAddress address = literal(addressLiteral);
		if (null == address)
			return false;
		byte[] bytes = address.getAddress();
		if (bytes.length != network.length)
			return false;
		int full = bits / 8;
		for (int i = 0; i < full; i++)
			if (bytes[i] != network[i])
				return false;
		int rest = bits % 8;
		if (0 == rest)
			return true;
		int mask = 0xFF << (8 - rest);
		return (bytes[full] & mask) == (network[full] & mask);
	}

	@Override
	public String toString() {
		return text;
	}
}
