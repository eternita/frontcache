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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A resolver the tests set up - no network - and a clock they move. */
final class FakeDns implements Dns {

	/** A reverse lookup of an address mapped to this throws, as a resolver that does not answer. */
	static final String FAIL = "!";

	/** address (canonical literal) -> its PTR name */
	final Map<String, String> names = new ConcurrentHashMap<>();
	/** name -> the addresses it resolves to */
	final Map<String, List<String>> addresses = new ConcurrentHashMap<>();
	final AtomicInteger reverses = new AtomicInteger();
	final AtomicInteger forwards = new AtomicInteger();
	/** When set, every reverse lookup waits for it. */
	volatile CountDownLatch gate;

	static String canonical(String address) {
		return InetAddress.ofLiteral(address).getHostAddress();
	}

	/** A crawler's address: its name, and the name resolving back to it. */
	FakeDns crawler(String address, String host) {
		names.put(canonical(address), host);
		addresses.computeIfAbsent(host, h -> new ArrayList<>()).add(canonical(address));
		return this;
	}

	/** An address whose PTR says {@code host}, which resolves to {@code forward} (nothing when empty). */
	FakeDns claims(String address, String host, String... forward) {
		names.put(canonical(address), host);
		List<String> to = new ArrayList<>();
		for (String a : forward)
			to.add(canonical(a));
		addresses.put(host.toLowerCase(java.util.Locale.ROOT).replaceAll("\\.$", ""), to);
		return this;
	}

	@Override
	public String reverse(InetAddress address) throws IOException {
		reverses.incrementAndGet();
		CountDownLatch g = gate;
		if (null != g)
			try {
				g.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted");
			}
		String name = names.get(address.getHostAddress());
		if (FAIL.equals(name))
			throw new IOException("SERVFAIL");
		return name;
	}

	@Override
	public List<InetAddress> forward(String host) throws IOException {
		forwards.incrementAndGet();
		List<String> to = addresses.get(host);
		if (null == to)
			throw new java.net.UnknownHostException(host);
		List<InetAddress> out = new ArrayList<>();
		for (String a : to)
			out.add(InetAddress.ofLiteral(a));
		return out;
	}

	/** A clock the test moves. */
	static final class MovableClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T10:00:00Z"));

		void advance(Duration by) {
			now.updateAndGet(t -> t.plus(by));
		}

		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
