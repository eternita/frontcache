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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FcNodeStats} - the JVM numbers behind the management API's pollable node view.
 *
 * Two things are worth pinning here. That the live readers actually return live values, because a
 * silently-unknown heap would render as an em dash in the console and look like a node problem rather
 * than a Frontcache one; and that "unknown" stays distinguishable from zero, which is the rule the
 * whole response shape depends on.
 */
public class FcNodeStatsTest {

	// ---- the live readers --------------------------------------------------------------------

	@Test
	public void heapIsReadFromTheRunningJvm()
	{
		FcNodeStats stats = FcNodeStats.current();

		assertTrue("a running JVM is using some heap", stats.getHeapUsedBytes() > 0);
		assertTrue("heap must be tracked", stats.isTracked(stats.getHeapUsedBytes()));
	}

	@Test
	public void uptimeIsReadFromTheRunningJvm()
	{
		FcNodeStats stats = FcNodeStats.current();

		assertTrue("a JVM running this test has been up for a moment: " + stats.getUptimeMillis(),
				stats.getUptimeMillis() > 0);
	}

	/**
	 * CPU is allowed to be unknown - the HotSpot bean returns a negative until it has two samples of
	 * its own, and a non-HotSpot JVM has no such bean at all. What it must never be is a number outside
	 * 0..1, because the console renders it as a percentage.
	 */
	@Test
	public void cpuIsEitherAFractionOrUnknown()
	{
		double load = FcNodeStats.current().getCpuProcessLoad();

		assertTrue("cpu must be 0..1 or UNKNOWN, was " + load,
				FcNodeStats.UNKNOWN_LOAD == load || (load >= 0.0 && load <= 1.0));
	}

	/** It sits behind a polled endpoint: a reading must never be the thing that fails the response. */
	@Test
	public void readingIsRepeatableAndNeverThrows()
	{
		for (int i = 0; i < 100; i++)
			assertNotNull(FcNodeStats.current());
	}

	@Test
	public void uptimeDoesNotGoBackwards()
	{
		long first = FcNodeStats.current().getUptimeMillis();
		long second = FcNodeStats.current().getUptimeMillis();

		assertTrue(second + " < " + first, second >= first);
	}

	// ---- unknown is not zero -----------------------------------------------------------------

	@Test
	public void notTrackedReportsUnknownForEveryField()
	{
		FcNodeStats stats = FcNodeStats.NOT_TRACKED;

		assertEquals(FcNodeStats.UNKNOWN, stats.getHeapUsedBytes());
		assertEquals(FcNodeStats.UNKNOWN, stats.getHeapMaxBytes());
		assertEquals(FcNodeStats.UNKNOWN, stats.getUptimeMillis());
		assertEquals(FcNodeStats.UNKNOWN_LOAD, stats.getCpuProcessLoad(), 0.0);

		assertFalse(stats.isTracked(stats.getHeapUsedBytes()));
		assertFalse(stats.isTracked(stats.getCpuProcessLoad()));
	}

	/**
	 * An unbounded heap has no ceiling to divide by, and {@code MemoryUsage.getMax()} says so with -1.
	 * That has to arrive as UNKNOWN rather than as a ceiling of zero, or "used / max" renders as a
	 * node at infinite capacity.
	 */
	@Test
	public void anUndefinedHeapMaximumIsUnknownRatherThanZero()
	{
		FcNodeStats unbounded = new FcNodeStats(1024L, FcNodeStats.UNKNOWN, 0.5, 60_000L);

		assertFalse("an undefined maximum is not a tracked number", unbounded.isTracked(unbounded.getHeapMaxBytes()));
		assertEquals("and it is not zero either", FcNodeStats.UNKNOWN, unbounded.getHeapMaxBytes());
		assertTrue("while the used figure beside it stays tracked", unbounded.isTracked(unbounded.getHeapUsedBytes()));
	}

	/** Zero CPU is a real reading on an idle node, and must not be confused with "no reading". */
	@Test
	public void zeroCpuIsTrackedButNegativeCpuIsNot()
	{
		assertTrue("an idle node reports 0.0 and that is a measurement",
				new FcNodeStats(1L, 2L, 0.0, 3L).isTracked(0.0));
		assertFalse(new FcNodeStats(1L, 2L, FcNodeStats.UNKNOWN_LOAD, 3L).isTracked(FcNodeStats.UNKNOWN_LOAD));
	}

	@Test
	public void unknownIsNotConfusableWithZero()
	{
		assertTrue(FcNodeStats.UNKNOWN < 0);
		assertTrue(FcNodeStats.UNKNOWN_LOAD < 0);
	}

}
