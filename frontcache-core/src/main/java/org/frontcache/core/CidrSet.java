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
package org.frontcache.core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A set of {@link Cidr} blocks that answers "is this address in one of them" in a handful of hash lookups, however
 * many blocks it holds - what a {@code client-ip:} rule asks on every request it reaches.
 *
 * <p>Until 2.13.0 a rule walked its blocks one by one and each block parsed the visitor's address again. Harmless for
 * the dozen blocks an operator writes by hand; not for a list a plugin maintains ({@code conf/lists/}, written
 * through {@code PluginContext.writeList}), which may hold tens of thousands of single addresses - a ban list - and
 * would have cost every request that many comparisons and address parses. Here the address is parsed once, and the
 * blocks are grouped by family and prefix length: one masked lookup per distinct prefix length the set holds (at most
 * 33 for IPv4, 129 for IPv6; a list of single addresses is one).
 *
 * <p>Immutable, so a reader needs no lock. Matching is {@link Cidr#contains}'s: by raw address bytes, so an IPv4 block
 * never covers an IPv6 address (an IPv4-mapped literal is parsed by the JDK as the IPv4 address).
 */
public final class CidrSet {

	/** The set with no block: contains nothing. */
	public static final CidrSet EMPTY = new CidrSet(List.of());

	/** An IPv6 network, masked: two halves of the 128 bits. */
	private record V6(long high, long low) {
	}

	private final int size;
	private final int[] v4Prefixes;
	private final Set<Long>[] v4Networks;
	private final int[] v6Prefixes;
	private final Set<V6>[] v6Networks;

	@SuppressWarnings("unchecked")
	private CidrSet(Collection<Cidr> blocks) {
		Map<Integer, Set<Long>> v4 = new HashMap<>();
		Map<Integer, Set<V6>> v6 = new HashMap<>();
		int count = 0;
		for (Cidr block : blocks) {
			if (null == block)
				continue;
			count++;
			byte[] network = block.networkBytes();
			int prefix = block.prefixBits();
			if (4 == network.length)
				v4.computeIfAbsent(prefix, p -> new HashSet<>()).add(v4(network, prefix));
			else
				v6.computeIfAbsent(prefix, p -> new HashSet<>()).add(v6(network, prefix));
		}
		this.size = count;

		// longest prefix first: a set of single addresses answers in its first lookup
		List<Integer> v4Order = new ArrayList<>(new TreeSet<>(v4.keySet()).descendingSet());
		this.v4Prefixes = new int[v4Order.size()];
		this.v4Networks = new Set[v4Order.size()];
		for (int i = 0; i < v4Order.size(); i++) {
			v4Prefixes[i] = v4Order.get(i);
			v4Networks[i] = Set.copyOf(v4.get(v4Order.get(i)));
		}
		List<Integer> v6Order = new ArrayList<>(new TreeSet<>(v6.keySet()).descendingSet());
		this.v6Prefixes = new int[v6Order.size()];
		this.v6Networks = new Set[v6Order.size()];
		for (int i = 0; i < v6Order.size(); i++) {
			v6Prefixes[i] = v6Order.get(i);
			v6Networks[i] = Set.copyOf(v6.get(v6Order.get(i)));
		}
	}

	/** @param blocks the blocks, nulls skipped; the same block twice counts twice in {@link #size()} */
	public static CidrSet of(Collection<Cidr> blocks) {
		return (null == blocks || blocks.isEmpty()) ? EMPTY : new CidrSet(blocks);
	}

	/** How many blocks it was built from. */
	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return 0 == size;
	}

	/**
	 * @param addressLiteral an address as {@link Cidr#parseAddress} reads it (a trailing port, brackets and a zone id
	 *                       are tolerated) - never a host name, so this never looks anything up
	 * @return whether it is in one of the blocks; false for null or anything that is not an address
	 */
	public boolean contains(String addressLiteral) {
		if (0 == size || null == addressLiteral)
			return false;
		return contains(Cidr.parseAddress(addressLiteral));
	}

	/** @return whether the address is in one of the blocks; false for null */
	public boolean contains(InetAddress address) {
		if (0 == size || null == address)
			return false;
		byte[] bytes;
		try {
			bytes = address.getAddress();
		} catch (Exception e) {
			return false;
		}
		if (4 == bytes.length) {
			for (int i = 0; i < v4Prefixes.length; i++)
				if (v4Networks[i].contains(v4(bytes, v4Prefixes[i])))
					return true;
			return false;
		}
		if (16 == bytes.length) {
			for (int i = 0; i < v6Prefixes.length; i++)
				if (v6Networks[i].contains(v6(bytes, v6Prefixes[i])))
					return true;
		}
		return false;
	}

	private static long v4(byte[] bytes, int prefix) {
		long value = ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16) | ((bytes[2] & 0xFFL) << 8) | (bytes[3] & 0xFFL);
		long mask = (0 == prefix) ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
		return value & mask;
	}

	private static V6 v6(byte[] bytes, int prefix) {
		long high = 0;
		long low = 0;
		for (int i = 0; i < 8; i++)
			high = (high << 8) | (bytes[i] & 0xFFL);
		for (int i = 8; i < 16; i++)
			low = (low << 8) | (bytes[i] & 0xFFL);
		return new V6(high & mask64(prefix), low & mask64(prefix - 64));
	}

	/** The top {@code bits} of 64 set; none for 0 or less, all for 64 or more. */
	private static long mask64(int bits) {
		if (bits <= 0)
			return 0L;
		if (bits >= 64)
			return -1L;
		return -1L << (64 - bits);
	}
}
