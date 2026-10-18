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
package org.frontcache.core;

import java.net.InetAddress;

/**
 * An address block, compared on raw address bytes so v4 and v6 never match each other.
 *
 * Lived inside {@link ClientIpResolver} as a package-private nested class until a second caller
 * appeared in another package (the {@code client-ip:} rule predicate, shared by guard rules and
 * bots.conf - see docs/archive/dynamic-bots-classification-proposal.md). Promoted rather than copied: one
 * definition of "is this address inside that block", so a fix reaches both callers.
 *
 * Never throws. An address or block that cannot be parsed is null, and every caller treats null as
 * "undecidable", not as "matches".
 */
public final class Cidr {

	private final byte[] network;
	private final int prefixBits;
	private final String source;

	private Cidr(byte[] network, int prefixBits, String source) {
		this.network = network;
		this.prefixBits = prefixBits;
		this.source = source;
	}

	/**
	 * @param text an address ({@code 203.0.113.7}, {@code 2001:db8::1}) or a block
	 *             ({@code 10.0.0.0/8}). A bare address is a single-host block.
	 * @return the block, or null when the text is not one
	 */
	public static Cidr parse(String text)
	{
		if (null == text)
			return null;

		String value = text.trim();
		int prefixBits = -1;

		int slash = value.lastIndexOf('/');
		if (-1 < slash)
		{
			try {
				prefixBits = Integer.parseInt(value.substring(slash + 1).trim());
			} catch (NumberFormatException e) {
				return null;
			}
			value = value.substring(0, slash).trim();
		}

		InetAddress address = parseAddress(value);
		if (null == address)
			return null;

		byte[] bytes = address.getAddress();
		int maxBits = bytes.length * 8;

		if (-1 == prefixBits)
			prefixBits = maxBits;   // a bare address is a single-host block

		if (0 > prefixBits || prefixBits > maxBits)
			return null;

		return new Cidr(bytes, prefixBits, text.trim());
	}

	public boolean contains(InetAddress address)
	{
		if (null == address)
			return false;

		byte[] bytes;
		try {
			bytes = address.getAddress();
		} catch (Exception e) {
			return false;
		}

		if (bytes.length != network.length)
			return false; // different family - never a match, which is what keeps a v4 block from covering v6

		int fullBytes = prefixBits / 8;
		for (int i = 0; i < fullBytes; i++)
			if (bytes[i] != network[i])
				return false;

		int remainingBits = prefixBits % 8;
		if (0 == remainingBits)
			return true;

		int mask = 0xFF << (8 - remainingBits);
		return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
	}

	/**
	 * @param text an address literal, as it turns up in remoteAddr and forwarding headers
	 * @return the address, or null when the text is not an IP literal
	 */
	public boolean contains(String addressLiteral) {
		return contains(parseAddress(addressLiteral));
	}

	/**
	 * Parses an address literal only - never a host name, so this can never trigger a DNS lookup on
	 * the request thread from attacker-controlled input. Tolerates the forms that turn up in
	 * remoteAddr and forwarding headers: bracketed IPv6, a trailing port, an IPv6 zone id.
	 *
	 * @return the address, or null if the text is not an IP literal
	 */
	public static InetAddress parseAddress(String text)
	{
		if (null == text)
			return null;

		String value = text.trim();
		if (0 == value.length())
			return null;

		if (value.startsWith("["))
		{
			int close = value.indexOf(']');
			if (-1 == close)
				return null;
			value = value.substring(1, close);           // [2001:db8::1]:443 -> 2001:db8::1
		}
		else
		{
			int colon = value.indexOf(':');
			if (-1 < colon && colon == value.lastIndexOf(':'))
				value = value.substring(0, colon);       // 203.0.113.7:1234 -> 203.0.113.7 (one colon = IPv4:port)
		}

		int zone = value.indexOf('%');
		if (-1 < zone)
			value = value.substring(0, zone);            // fe80::1%eth0 -> fe80::1

		if (0 == value.length())
			return null;

		try {
			return InetAddress.ofLiteral(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Override
	public String toString() {
		return source;
	}

}
