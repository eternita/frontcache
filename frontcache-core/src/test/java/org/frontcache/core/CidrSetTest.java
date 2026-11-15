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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * {@link CidrSet} answers exactly what walking its blocks with {@link Cidr#contains} answers - it is an index, never a
 * second definition of "in the block".
 */
public class CidrSetTest {

	private static CidrSet set(String... blocks) {
		List<Cidr> parsed = new ArrayList<>();
		for (String b : blocks)
			parsed.add(Cidr.parse(b));
		return CidrSet.of(parsed);
	}

	@Test
	public void singleAddressesAndBlocksOfBothFamilies() {
		CidrSet set = set("203.0.113.7", "10.0.0.0/8", "198.51.100.128/25", "2001:db8::1", "2001:db8:aa::/48");
		assertEquals(5, set.size());
		assertTrue(set.contains("203.0.113.7"));
		assertFalse(set.contains("203.0.113.8"));
		assertTrue(set.contains("10.255.1.2"));
		assertFalse(set.contains("11.0.0.1"));
		assertTrue(set.contains("198.51.100.200"));
		assertFalse(set.contains("198.51.100.127"));
		assertTrue(set.contains("2001:db8::1"));
		assertTrue(set.contains("[2001:db8:0:0::1]:443"));
		assertFalse(set.contains("2001:db8::2"));
		assertTrue(set.contains("2001:db8:aa:ffff::9"));
		assertFalse(set.contains("2001:db8:ab::9"));
	}

	@Test
	public void aBlockWrittenWithHostBitsCoversItsNetwork() {
		CidrSet set = set("10.1.2.3/8", "2001:db8::77/32");
		assertTrue(set.contains("10.9.9.9"));
		assertTrue(set.contains("2001:db8:ffff::1"));
	}

	@Test
	public void theFamiliesNeverCoverEachOther() {
		assertFalse(set("0.0.0.0/0").contains("2001:db8::1"));
		assertTrue(set("0.0.0.0/0").contains("192.0.2.1"));
		assertFalse(set("::/0").contains("192.0.2.1"));
		assertTrue(set("::/0").contains("::1"));
	}

	@Test
	public void nothingThatIsNotAnAddressIsInIt() {
		CidrSet set = set("0.0.0.0/0", "::/0");
		assertFalse(set.contains((String) null));
		assertFalse(set.contains(""));
		assertFalse(set.contains("localhost"));
		assertFalse(set.contains("not an address"));
		assertFalse(CidrSet.EMPTY.contains("192.0.2.1"));
		assertTrue(CidrSet.of(null).isEmpty());
		assertTrue(CidrSet.of(Arrays.asList((Cidr) null)).isEmpty());
	}

	@Test
	public void anIndexOfTheSameAnswersAsAWalk() {
		Random random = new Random(20261115);
		List<Cidr> blocks = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			int prefix = (0 == i % 5) ? 8 + random.nextInt(25) : 32;
			blocks.add(Cidr.parse(random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "."
					+ random.nextInt(256) + "/" + prefix));
			int prefix6 = (0 == i % 5) ? 16 + random.nextInt(113) : 128;
			blocks.add(Cidr.parse(String.format("2001:db8:%x:%x::%x/%d", random.nextInt(4), random.nextInt(65536),
					random.nextInt(65536), prefix6)));
		}
		CidrSet set = CidrSet.of(blocks);
		int hits = 0;
		for (int i = 0; i < 20_000; i++) {
			String address = (0 == i % 2)
					? random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256)
					: String.format("2001:db8:%x:%x::%x", random.nextInt(4), random.nextInt(65536), random.nextInt(65536));
			boolean walked = false;
			for (Cidr block : blocks)
				if (block.contains(address)) {
					walked = true;
					break;
				}
			assertEquals(address, walked, set.contains(address));
			if (walked)
				hits++;
		}
		assertTrue("the corpus exercises both answers: " + hits, 0 < hits && hits < 20_000);
	}
}
